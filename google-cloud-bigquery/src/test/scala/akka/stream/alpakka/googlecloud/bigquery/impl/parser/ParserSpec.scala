/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.bigquery.impl.parser

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.alpakka.googlecloud.bigquery.impl.parser.Parser.PagingInfo
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.JsObject

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ParserSpec extends TestKit(ActorSystem("ParserSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val pageToken = "dummyToken"
  val jobId = "dummyJobId"
  val response = HttpResponse(
    entity = HttpEntity(s"""{"pageToken": "$pageToken", "jobReference": { "jobId": "$jobId" } }""")
  )
  val responseWithoutJobId = HttpResponse(entity = HttpEntity(s"""{"pageToken": "$pageToken"}"""))

  def createTestGraph[Data, S1, S2](source: Source[HttpResponse, _],
                                    dataSink: Sink[Data, S1],
                                    pageSink: Sink[(Boolean, PagingInfo), S2],
                                    parseFunction: JsObject => Try[Data]): RunnableGraph[(S1, S2)] =
    RunnableGraph.fromGraph(GraphDSL.create(dataSink, pageSink)((_, _)) { implicit builder => (s1, s2) =>
      import GraphDSL.Implicits._

      val parser = builder.add(Parser[Data](parseFunction))

      source ~> parser.in
      parser.out0 ~> s1
      parser.out1 ~> s2

      ClosedShape
    })

  "Parser" should {

    "output the value returned by the parse function" in {
      val testGraph =
        createTestGraph(Source.single(response),
                        TestSink.probe[String],
                        TestSink.probe[(Boolean, PagingInfo)],
                        _ => Try(pageToken))

      val (dataSink, pageSink) = testGraph.run()

      Future(pageSink.requestNext())

      val result = dataSink.requestNext(3.seconds)

      result should be(pageToken)
    }

    "output the page token and jobid parsed from the http response" in {
      val testGraph =
        createTestGraph(Source.single(response),
                        TestSink.probe[JsObject],
                        TestSink.probe[(Boolean, PagingInfo)],
                        x => Try(x))

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val pagingInfo = pageSink.requestNext(3.seconds)

      pagingInfo should be((false, PagingInfo(Some(pageToken), Some(jobId))))
    }

    "output the page token parsed from the http response" in {
      val testGraph = createTestGraph(Source.single(responseWithoutJobId),
                                      TestSink.probe[JsObject],
                                      TestSink.probe[(Boolean, PagingInfo)],
                                      x => Try(x))

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val pagingInfo = pageSink.requestNext(3.seconds)

      pagingInfo should be((false, PagingInfo(Some(pageToken), None)))
    }

    "output none for page token when http response does not contain page token" in {
      val testGraph = createTestGraph(Source.single(HttpResponse(entity = HttpEntity("{}"))),
                                      TestSink.probe[JsObject],
                                      TestSink.probe[(Boolean, PagingInfo)],
                                      x => Try(x))

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val pagingInfo = pageSink.requestNext(3.seconds)

      pagingInfo should be((false, PagingInfo(None, None)))
    }

    "handles parser None" in {

      def failureParser(result: JsObject): Try[String] = Try {
        throw new Exception
      }

      val testGraph =
        createTestGraph(Source.single(response),
                        TestSink.probe[String],
                        TestSink.probe[(Boolean, PagingInfo)],
                        failureParser)

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val result = pageSink.requestNext(3.seconds)

      result should be((true, PagingInfo(Some(pageToken), Some(jobId))))
    }

  }
}
