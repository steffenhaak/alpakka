/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.pubsub

import java.time.Instant
import java.util.Base64

import akka.actor.ActorSystem
import akka.http.scaladsl.HttpExt
import akka.stream.alpakka.googlecloud.pubsub.impl.{GoogleSession, PubSubApi, TestCredentials}
import akka.stream.alpakka.googlecloud.pubsub.scaladsl.GooglePubSub
import akka.stream.alpakka.testkit.scaladsl.LogCapturing
import akka.stream.scaladsl.{Flow, FlowWithContext, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.{Done, NotUsed}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class GooglePubSubSpec
    extends AnyFlatSpec
    with MockitoSugar
    with ScalaFutures
    with Matchers
    with LogCapturing
    with BeforeAndAfterAll {

  implicit val defaultPatience =
    PatienceConfig(timeout = 5.seconds, interval = 100.millis)

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  private trait Fixtures {
    lazy val mockHttpApi = mock[PubSubApi]
    lazy val googlePubSub = new GooglePubSub {
      override val httpApi = mockHttpApi
    }
    def tokenFlowWithContext[T, C]: FlowWithContext[T, C, (T, Option[String]), C, NotUsed] =
      FlowWithContext[T, C].map((_, Some("ok")))

    def tokenFlow[T]: Flow[T, (T, Option[String]), NotUsed] =
      Flow[T].map(request => (request, Some("ok")))

    val http: HttpExt = mock[HttpExt]
    val config = PubSubConfig(TestCredentials.projectId, TestCredentials.clientEmail, TestCredentials.privateKey)
      .withSession(mock[GoogleSession])
  }

  it should "auth and publish the message" in new Fixtures {

    val request = PublishRequest(Seq(PublishMessage(data = base64String("Hello Google!"))))

    val source = Source(List(request))

    when(mockHttpApi.isEmulated).thenReturn(false)
    when(mockHttpApi.accessTokenWithContext(config = config)).thenReturn(tokenFlowWithContext)
    when(
      mockHttpApi.publish[Unit](project = TestCredentials.projectId, topic = "topic1", parallelism = 1)
    ).thenReturn(FlowWithContext[(PublishRequest, Option[String]), Unit].map(_ => Seq("id1")))

    val flow = googlePubSub.publish(
      topic = "topic1",
      config = config
    )
    val result = source.via(flow).runWith(Sink.seq)

    result.futureValue shouldBe Seq(Seq("id1"))
  }

  it should "auth and publish the message with context" in new Fixtures {

    val request = PublishRequest(Seq(PublishMessage(data = base64String("Hello Google!"))))

    val source = Source(List((request, "correlationId")))

    when(mockHttpApi.isEmulated).thenReturn(false)
    when(mockHttpApi.accessTokenWithContext(config = config)).thenReturn(tokenFlowWithContext)
    when(
      mockHttpApi.publish[String](project = TestCredentials.projectId, topic = "topic1", parallelism = 1)
    ).thenReturn(FlowWithContext[(PublishRequest, Option[String]), String].map(_ => Seq("id1")))

    val flow = googlePubSub.publishWithContext[String](
      topic = "topic1",
      config = config
    )
    val result = source.via(flow).runWith(Sink.seq)

    result.futureValue shouldBe Seq((Seq("id1"), "correlationId"))
  }

  it should "publish the message without auth when emulated" in new Fixtures {

    override lazy val mockHttpApi = new PubSubApi {
      val PubSubGoogleApisHost: String = "..."
      val PubSubGoogleApisPort = 80

      override def isEmulated: Boolean = true

      override def publish[T](
          project: String,
          topic: String,
          parallelism: Int
      )(implicit as: ActorSystem,
        materializer: Materializer): FlowWithContext[(PublishRequest, Option[String]), T, Seq[String], T, NotUsed] =
        FlowWithContext[(PublishRequest, Option[String]), T].map(_ => Seq("id2"))
    }

    val flow = googlePubSub.publishWithContext[Unit](
      topic = "topic2",
      config = config
    )

    val request = PublishRequest(Seq(PublishMessage(data = base64String("Hello Google!"))))

    val source = Source(List((request, ())))
    val result = source.via(flow).runWith(Sink.seq)
    result.futureValue shouldBe Seq((Seq("id2"), ()))
  }

  it should "subscribe and pull a message" in new Fixtures {
    val publishTime = Instant.ofEpochMilli(111)
    val message =
      ReceivedMessage(
        ackId = "1",
        message = PubSubMessage(messageId = "1", data = Some(base64String("Hello Google!")), publishTime = publishTime)
      )
    private def flow(messages: Seq[ReceivedMessage]): Flow[(Done, Option[String]), PullResponse, NotUsed] =
      Flow[(Done, Option[String])]
        .map(_ => PullResponse(receivedMessages = Some(messages)))

    when(mockHttpApi.accessToken(config = config)).thenReturn(tokenFlow)
    when(
      mockHttpApi.pull(project = TestCredentials.projectId,
                       subscription = "sub1",
                       returnImmediately = true,
                       maxMessages = 1000)
    ).thenReturn(flow(Seq(message)))

    val source = googlePubSub.subscribe(
      subscription = "sub1",
      config = config
    )

    val result = source.take(1).runWith(Sink.seq)

    result.futureValue shouldBe Seq(message)
  }

  it should "auth and acknowledge a message" in new Fixtures {
    when(mockHttpApi.accessToken(config = config)).thenReturn(tokenFlow)
    when(
      mockHttpApi.acknowledge(
        project = TestCredentials.projectId,
        subscription = "sub1"
      )
    ).thenReturn(Flow[(AcknowledgeRequest, Option[String])].map(_ => Done))

    val sink = googlePubSub.acknowledge(
      subscription = "sub1",
      config = config
    )

    val source = Source(List(AcknowledgeRequest("a1")))

    val result = source.runWith(sink)

    result.futureValue shouldBe Done
  }

  it should "acknowledge a message without auth when emulated" in new Fixtures {
    override lazy val mockHttpApi = new PubSubApi {
      val PubSubGoogleApisHost: String = "..."
      val PubSubGoogleApisPort = 80

      override def isEmulated: Boolean = true
      override def acknowledge(project: String, subscription: String)(
          implicit as: ActorSystem,
          materializer: Materializer
      ): Flow[(AcknowledgeRequest, Option[String]), Done, NotUsed] =
        Flow[(AcknowledgeRequest, Option[String])].map { case (_, _) => Done }
    }

    val sink = googlePubSub.acknowledge(
      subscription = "sub1",
      config = config
    )

    val source = Source(List(AcknowledgeRequest("a1")))

    val result = source.runWith(sink)

    result.futureValue shouldBe Done
  }

  def base64String(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))
}
