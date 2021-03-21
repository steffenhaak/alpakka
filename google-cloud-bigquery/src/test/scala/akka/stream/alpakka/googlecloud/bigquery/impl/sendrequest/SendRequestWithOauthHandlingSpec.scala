/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.bigquery.impl.sendrequest

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.{HttpExt, HttpsConnectionContext}
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import akka.stream.alpakka.googlecloud.bigquery.e2e.BigQueryTableHelper
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import akka.util.Timeout
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.mockito.ArgumentMatchers._
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Try}

class SendRequestWithOauthHandlingSpec
    extends TestKit(ActorSystem("SendRequestWithOauthHandling"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BigQueryTableHelper
    with MockitoSugar {

  override implicit val actorSystem: ActorSystem = ActorSystem("BigQueryEndToEndSpec")
  override implicit val materializer: ActorMaterializer = ActorMaterializer()

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(1.second)

  "SendRequestWithOauthHandling" must {

    "handle unexpected http error" in {

      val http = mock[HttpExt]
      when(
        http.singleRequest(
          any[HttpRequest],
          any[HttpsConnectionContext],
          any[ConnectionPoolSettings],
          any[LoggingAdapter]
        )
      ) thenReturn Future.successful(HttpResponse(StatusCodes.InternalServerError, Nil, HttpEntity("my custom error")))

      val resultF = Source
        .single(HttpRequest())
        .via(SendRequestWithOauthHandling(projectConfig, http))
        .runWith(Sink.last)

      val result = Try(Await.result(resultF, 10.second))
      result.toString shouldEqual Failure(
        new IllegalStateException(s"Unexpected error in response: 500 Internal Server Error, my custom error")
      ).toString
    }

  }

}
