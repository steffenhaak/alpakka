/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.elasticsearch.scaladsl

import akka.NotUsed
import akka.stream.alpakka.elasticsearch._
import akka.stream.alpakka.elasticsearch.impl
import akka.stream.scaladsl.Source
import org.elasticsearch.client.RestClient
import spray.json._

/**
 * Scala API to create Elasticsearch sources.
 */
object ElasticsearchSource {

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s
   * of Spray's [[spray.json.JsObject]].
   * Alias of [[create]].
   */
  def apply(indexName: String,
            typeName: String,
            query: String,
            settings: ElasticsearchSourceSettings = ElasticsearchSourceSettings.Default)(
      implicit elasticsearchClient: RestClient
  ): Source[ReadResult[JsObject], NotUsed] = create(indexName, typeName, query, settings)

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s
   * of Spray's [[spray.json.JsObject]].
   * Alias of [[create]].
   *
   * Example of searchParams-usage:
   *  Map( "query" -> """{"match_all": {}}""" )
   *  Map( "query" -> """{"match_all": {}}""", "_source" -> """ ["fieldToInclude", "anotherFieldToInclude"] """ )
   */
  def apply(indexName: String,
            typeName: Option[String],
            searchParams: Map[String, String],
            settings: ElasticsearchSourceSettings)(
      implicit elasticsearchClient: RestClient
  ): Source[ReadResult[JsObject], NotUsed] = create(indexName, typeName, searchParams, settings)

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s
   * of Spray's [[spray.json.JsObject]].
   */
  def create(indexName: String,
             typeName: String,
             query: String,
             settings: ElasticsearchSourceSettings = ElasticsearchSourceSettings.Default)(
      implicit elasticsearchClient: RestClient
  ): Source[ReadResult[JsObject], NotUsed] =
    create(indexName, Option(typeName), query, settings)

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s
   * of Spray's [[spray.json.JsObject]].
   */
  def create(indexName: String, typeName: Option[String], query: String, settings: ElasticsearchSourceSettings)(
      implicit elasticsearchClient: RestClient
  ): Source[ReadResult[JsObject], NotUsed] =
    create(indexName, typeName, Map("query" -> query), settings)

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s
   * of Spray's [[spray.json.JsObject]].
   *
   * Example of searchParams-usage:
   *  Map( "query" -> """{"match_all": {}}""" )
   *  Map( "query" -> """{"match_all": {}}""", "_source" -> """ ["fieldToInclude", "anotherFieldToInclude"] """ )
   */
  def create(indexName: String,
             typeName: Option[String],
             searchParams: Map[String, String],
             settings: ElasticsearchSourceSettings)(
      implicit elasticsearchClient: RestClient
  ): Source[ReadResult[JsObject], NotUsed] = {
    ElasticsearchFlow.checkClient(elasticsearchClient)
    Source.fromGraph(
      new impl.ElasticsearchSourceStage(
        indexName,
        typeName,
        searchParams,
        elasticsearchClient,
        settings,
        new SprayJsonReader[JsObject]()(DefaultJsonProtocol.RootJsObjectFormat)
      )
    )
  }

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s of type `T`
   * converted by Spray's [[spray.json.JsonReader]]
   */
  def typed[T](indexName: String,
               typeName: String,
               query: String,
               settings: ElasticsearchSourceSettings = ElasticsearchSourceSettings.Default)(
      implicit elasticsearchClient: RestClient,
      reader: JsonReader[T]
  ): Source[ReadResult[T], NotUsed] =
    typed(indexName, Option(typeName), query, settings)

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s of type `T`
   * converted by Spray's [[spray.json.JsonReader]]
   */
  def typed[T](indexName: String, typeName: Option[String], query: String, settings: ElasticsearchSourceSettings)(
      implicit elasticsearchClient: RestClient,
      sprayJsonReader: JsonReader[T]
  ): Source[ReadResult[T], NotUsed] =
    typed(indexName, typeName, Map("query" -> query), settings)

  /**
   * Creates a [[akka.stream.scaladsl.Source]] from Elasticsearch that streams [[ReadResult]]s of type `T`
   * converted by Spray's [[spray.json.JsonReader]]
   *
   * Example of searchParams-usage:
   *  Map( "query" -> """{"match_all": {}}""" )
   *  Map( "query" -> """{"match_all": {}}""", "_source" -> """ ["fieldToInclude", "anotherFieldToInclude"] """ )
   */
  def typed[T](indexName: String,
               typeName: Option[String],
               searchParams: Map[String, String],
               settings: ElasticsearchSourceSettings)(
      implicit elasticsearchClient: RestClient,
      sprayJsonReader: JsonReader[T]
  ): Source[ReadResult[T], NotUsed] = {
    ElasticsearchFlow.checkClient(elasticsearchClient)
    Source.fromGraph(
      new impl.ElasticsearchSourceStage(indexName,
                                        typeName,
                                        searchParams,
                                        elasticsearchClient,
                                        settings,
                                        new SprayJsonReader[T]()(sprayJsonReader))
    )
  }

  private final class SprayJsonReader[T](implicit reader: JsonReader[T]) extends impl.MessageReader[T] {

    override def convert(json: String): impl.ScrollResponse[T] = {
      val jsObj = json.parseJson.asJsObject
      jsObj.fields.get("error") match {
        case Some(error) => {
          impl.ScrollResponse(Some(error.toString), None)
        }
        case None => {
          val scrollId = jsObj.fields("_scroll_id").asInstanceOf[JsString].value
          val hits = jsObj.fields("hits").asJsObject.fields("hits").asInstanceOf[JsArray]
          val messages = hits.elements.reverse.map { element =>
            val doc = element.asJsObject
            val id = doc.fields("_id").asInstanceOf[JsString].value
            val source = doc.fields("_source").asJsObject
            // Maybe we got the _version-property
            val version: Option[Long] = doc.fields.get("_version").map(_.asInstanceOf[JsNumber].value.toLong)
            new ReadResult(id, source.convertTo[T], version)
          }
          impl.ScrollResponse(None, Some(impl.ScrollResult(scrollId, messages)))
        }
      }
    }

  }

}
