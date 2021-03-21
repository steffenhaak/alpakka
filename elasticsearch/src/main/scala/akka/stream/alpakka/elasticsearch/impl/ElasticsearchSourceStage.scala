/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.elasticsearch.impl

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import akka.annotation.InternalApi
import akka.stream.alpakka.elasticsearch.{ElasticsearchSourceSettings, ReadResult}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}
import akka.stream.{Attributes, Outlet, SourceShape}
import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicHeader
import org.elasticsearch.client.{Response, ResponseListener, RestClient}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * INTERNAL API
 */
@InternalApi
private[elasticsearch] case class ScrollResponse[T](error: Option[String], result: Option[ScrollResult[T]])

/**
 * INTERNAL API
 */
@InternalApi
private[elasticsearch] case class ScrollResult[T](scrollId: String, messages: Seq[ReadResult[T]])

/**
 * INTERNAL API
 */
@InternalApi
private[elasticsearch] trait MessageReader[T] {
  def convert(json: String): ScrollResponse[T]
}

/**
 * INTERNAL API
 */
@InternalApi
private[elasticsearch] final class ElasticsearchSourceStage[T](indexName: String,
                                                               typeName: Option[String],
                                                               searchParams: Map[String, String],
                                                               client: RestClient,
                                                               settings: ElasticsearchSourceSettings,
                                                               reader: MessageReader[T])
    extends GraphStage[SourceShape[ReadResult[T]]] {
  require(indexName != null, "You must define an index name")

  val out: Outlet[ReadResult[T]] = Outlet("ElasticsearchSource.out")
  override val shape: SourceShape[ReadResult[T]] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new ElasticsearchSourceLogic[T](indexName, typeName, searchParams, client, settings, out, shape, reader)

}

/**
 * INTERNAL API
 */
@InternalApi
private[elasticsearch] final class ElasticsearchSourceLogic[T](indexName: String,
                                                               typeName: Option[String],
                                                               searchParams: Map[String, String],
                                                               client: RestClient,
                                                               settings: ElasticsearchSourceSettings,
                                                               out: Outlet[ReadResult[T]],
                                                               shape: SourceShape[ReadResult[T]],
                                                               reader: MessageReader[T])
    extends GraphStageLogic(shape)
    with ResponseListener
    with OutHandler
    with StageLogging {

  private var scrollId: String = null
  private val responseHandler = getAsyncCallback[Response](handleResponse)
  private val failureHandler = getAsyncCallback[Throwable](handleFailure)

  private var waitingForElasticData = false
  private var pullIsWaitingForData = false
  private var dataReady: Option[ScrollResponse[T]] = None

  def sendScrollScanRequest(): Unit =
    try {
      waitingForElasticData = true

      if (scrollId == null) {
        log.debug("Doing initial search")

        // Add extra params to search
        val extraParams = Seq(
          if (!searchParams.contains("size")) {
            Some(("size" -> settings.bufferSize.toString))
          } else {
            None
          },
          // Tell elastic to return the documents '_version'-property with the search-results
          // http://nocf-www.elastic.co/guide/en/elasticsearch/reference/current/search-request-version.html
          // https://www.elastic.co/guide/en/elasticsearch/guide/current/optimistic-concurrency-control.html
          if (!searchParams.contains("version") && settings.includeDocumentVersion) {
            Some(("version" -> "true"))
          } else {
            None
          }
        )

        val baseMap = Map("scroll" -> settings.scroll, "sort" -> "_doc")
        val routingKey = "routing"
        val queryParams = searchParams.get(routingKey).fold(baseMap)(r => baseMap + (routingKey -> r))
        val completeParams = searchParams ++ extraParams.flatten - routingKey

        val searchBody = "{" + completeParams
            .map {
              case (name, json) =>
                "\"" + name + "\":" + json
            }
            .mkString(",") + "}"

        val endpoint: String = (indexName, typeName) match {
          case (i, Some(t)) => s"/$i/$t/_search"
          case (i, None) => s"/$i/_search"
        }
        client.performRequestAsync(
          "POST",
          endpoint,
          queryParams.asJava,
          new StringEntity(searchBody, StandardCharsets.UTF_8),
          this,
          new BasicHeader("Content-Type", "application/json")
        )
      } else {
        log.debug("Fetching next scroll")

        client.performRequestAsync(
          "POST",
          s"/_search/scroll",
          Map[String, String]().asJava,
          new StringEntity(Map("scroll" -> settings.scroll, "scroll_id" -> scrollId).toJson.toString,
                           StandardCharsets.UTF_8),
          this,
          new BasicHeader("Content-Type", "application/json")
        )
      }
    } catch {
      case ex: Exception => handleFailure(ex)
    }

  override def onFailure(exception: Exception) = failureHandler.invoke(exception)
  override def onSuccess(response: Response) = responseHandler.invoke(response)

  def handleFailure(ex: Throwable): Unit = {
    waitingForElasticData = false
    failStage(ex)
  }

  def handleResponse(res: Response): Unit = {
    waitingForElasticData = false
    val json = {
      val out = new ByteArrayOutputStream()
      try {
        res.getEntity.writeTo(out)
        new String(out.toByteArray, "UTF-8")
      } finally {
        out.close()
      }
    }

    val scrollResponse = reader.convert(json)

    if (pullIsWaitingForData) {
      log.debug("Received data from elastic. Downstream has already called pull and is waiting for data")
      pullIsWaitingForData = false
      if (handleScrollResponse(scrollResponse)) {
        // we should go and get more data
        sendScrollScanRequest()
      }
    } else {
      log.debug("Received data from elastic. Downstream have not yet asked for it")
      // This is a prefetch of data which we received before downstream has asked for it
      dataReady = Some(scrollResponse)
    }

  }

  // Returns true if we should continue to work
  def handleScrollResponse(scrollResponse: ScrollResponse[T]): Boolean =
    scrollResponse match {
      case ScrollResponse(Some(error), _) =>
        // Do not attempt to clear the scroll in the case of an error.
        failStage(new IllegalStateException(error))
        false
      case ScrollResponse(None, Some(result)) if result.messages.isEmpty =>
        clearScrollAsync()
        false
      case ScrollResponse(_, Some(result)) =>
        scrollId = result.scrollId
        log.debug("Pushing data downstream")
        emitMultiple(out, result.messages.toIterator)
        true
    }

  setHandler(out, this)

  override def onPull(): Unit =
    dataReady match {
      case Some(data) =>
        // We already have data ready
        log.debug("Downstream is pulling data and we already have data ready")
        if (handleScrollResponse(data)) {
          // We should go and get more data

          dataReady = None

          if (!waitingForElasticData) {
            sendScrollScanRequest()
          }

        }
      case None =>
        if (pullIsWaitingForData) throw new Exception("This should not happen: Downstream is pulling more than once")
        pullIsWaitingForData = true

        if (!waitingForElasticData) {
          log.debug("Downstream is pulling data. We must go and get it")
          sendScrollScanRequest()
        } else {
          log.debug("Downstream is pulling data. Already waiting for data")
        }
    }

  /**
   * When downstream finishes, it is important to attempt to clear the scroll.
   * As such, this handler initiates an async call to clear the scroll, and
   * then explicitly keeps the stage alive. [[clearScrollAsync()]] is responsible
   * for completing the stage.
   */
  override def onDownstreamFinish(): Unit = {
    clearScrollAsync()
    setKeepGoing(true)
  }

  /**
   * If the [[scrollId]] is non null, attempt to clear the scroll.
   * Complete the stage successfully, whether or not the clear call succeeds.
   * If the clear call fails, the scroll will eventually timeout.
   */
  def clearScrollAsync(): Unit = {
    if (scrollId == null) {
      log.debug("Scroll Id is null. Completing stage eagerly.")
      completeStage()
    } else {
      val listener = new ResponseListener {
        override def onSuccess(response: Response): Unit = {
          clearScrollAsyncHandler.invoke(Success(response))
        }
        override def onFailure(exception: Exception): Unit = {
          clearScrollAsyncHandler.invoke(Failure(exception))
        }
      }

      // Clear the scroll
      client.performRequestAsync(
        "DELETE",
        s"/_search/scroll/$scrollId",
        listener,
        new BasicHeader("Content-Type", "application/json")
      )
    }
  }

  private val clearScrollAsyncHandler = getAsyncCallback[Try[Response]]({ result =>
    {
      // Note: the scroll will expire, so there is no reason to consider a failed
      // clear as a reason to fail the stream.
      log.debug("Result of clearing the scroll: {}", result)
      completeStage()
    }
  })

}
