/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.pubsub.impl

import akka.actor.ActorSystem
import akka.stream.alpakka.googlecloud.pubsub.impl.GoogleTokenApi.AccessTokenExpiry
import akka.stream.alpakka.testkit.scaladsl.LogCapturing
import org.mockito.Mockito.{when, _}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class GoogleSessionSpec
    extends AnyFlatSpec
    with ScalaFutures
    with MockitoSugar
    with BeforeAndAfterAll
    with Matchers
    with LogCapturing {

  private trait Fixtures {
    val mockTokenApi = mock[GoogleTokenApi]
  }

  implicit val system = ActorSystem()

  val accessToken =
    "ya29.Elz4A2XkfGKJ4CoS5x_umUBHsvjGdeWQzu6gRRCnNXI0fuIyoDP_6aYktBQEOI4YAhLNgUl2OpxWQaN8Z3hd5YfFw1y4EGAtr2o28vSID-c8ul_xxHuudE7RmhH9sg"

  it should "ask for new session on first request" in new Fixtures {
    val session = new GoogleSession(TestCredentials.clientEmail, TestCredentials.privateKey, mockTokenApi)

    when(mockTokenApi.now).thenReturn(0)

    when(mockTokenApi.getAccessToken(TestCredentials.clientEmail, TestCredentials.privateKey))
      .thenReturn(Future.successful(AccessTokenExpiry(accessToken, 3600)))

    session.getToken().futureValue shouldBe accessToken

    verify(mockTokenApi, times(1))
      .getAccessToken(TestCredentials.clientEmail, TestCredentials.privateKey)
  }

  it should "uses the cached value for the second request" in new Fixtures {
    val session = new GoogleSession(TestCredentials.clientEmail, TestCredentials.privateKey, mockTokenApi) {
      this.maybeAccessToken = Some(Future.successful(AccessTokenExpiry(accessToken, 3600)))
    }
    when(mockTokenApi.now).thenReturn(0)

    session.getToken().futureValue shouldBe accessToken

    verify(mockTokenApi, times(0))
      .getAccessToken(TestCredentials.clientEmail, TestCredentials.privateKey)
  }

  it should "requests a new session if current is about to expire" in new Fixtures {
    val session = new GoogleSession(TestCredentials.clientEmail, TestCredentials.privateKey, mockTokenApi) {
      this.maybeAccessToken = Some(Future.successful(AccessTokenExpiry("t1", 3600)))
    }
    when(mockTokenApi.now).thenReturn(3599)
    when(
      mockTokenApi.getAccessToken(TestCredentials.clientEmail, TestCredentials.privateKey)
    ).thenReturn(Future.successful(AccessTokenExpiry(accessToken, 3600)))

    session.getToken().futureValue shouldBe accessToken

    verify(mockTokenApi, times(1))
      .getAccessToken(TestCredentials.clientEmail, TestCredentials.privateKey)
  }

  override def afterAll(): Unit =
    Await.result(system.terminate(), 5.seconds)
}
