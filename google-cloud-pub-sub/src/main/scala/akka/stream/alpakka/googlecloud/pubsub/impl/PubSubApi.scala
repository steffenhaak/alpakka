/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.pubsub.impl

import java.time.Instant

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.alpakka.googlecloud.pubsub._
import akka.stream.scaladsl.{Flow, FlowWithContext}
import akka.{Done, NotUsed}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * https://cloud.google.com/pubsub/docs/reference/rest/
 */
@InternalApi
private[pubsub] object PubSubApi extends PubSubApi {
  val DefaultPubSubGoogleApisHost = "pubsub.googleapis.com"
  val DefaultPubSubGoogleApisPort = 443
  val PubSubEmulatorHostVarName = "PUBSUB_EMULATOR_HOST"
  val PubSubEmulatorPortVarName = "PUBSUB_EMULATOR_PORT"

  val PubSubGoogleApisHost: String = PubSubEmulatorHost.getOrElse(DefaultPubSubGoogleApisHost)
  val PubSubGoogleApisPort: Int = PubSubEmulatorPort.getOrElse(DefaultPubSubGoogleApisPort)

  override def isEmulated = PubSubEmulatorHost.nonEmpty

  private[pubsub] lazy val PubSubEmulatorHost: Option[String] = sys.props
    .get(PubSubEmulatorHostVarName)
    .orElse(sys.env.get(PubSubEmulatorHostVarName))

  private[pubsub] lazy val PubSubEmulatorPort: Option[Int] = sys.props
    .get(PubSubEmulatorPortVarName)
    .orElse(sys.env.get(PubSubEmulatorPortVarName))
    .map(port => Int.unbox(Integer.valueOf(port)))

}

@InternalApi
private[pubsub] trait PubSubApi {
  def PubSubGoogleApisHost: String
  def PubSubGoogleApisPort: Int
  def isEmulated: Boolean

  private implicit val instantFormat = new RootJsonFormat[Instant] {
    override def read(jsValue: JsValue): Instant = jsValue match {
      case JsString(time) => Instant.parse(time)
      case _ => deserializationError("Instant required as a string of RFC3339 UTC Zulu format.")
    }
    override def write(instant: Instant): JsValue = JsString(instant.toString)
  }

  private implicit val pubSubMessageFormat =
    new RootJsonFormat[PubSubMessage] {
      override def read(json: JsValue): PubSubMessage = {
        val fields = json.asJsObject.fields
        PubSubMessage(
          fields.get("data").map(_.convertTo[String]),
          fields.get("attributes").map(_.convertTo[Map[String, String]]),
          fields("messageId").convertTo[String],
          fields("publishTime").convertTo[Instant]
        )
      }
      override def write(m: PubSubMessage): JsValue =
        JsObject(
          Seq(
            "messageId" -> m.messageId.toJson,
            "publishTime" -> m.publishTime.toJson
          )
          ++ m.data.map(data => "data" -> data.toJson)
          ++ m.attributes.map(attributes => "attributes" -> attributes.toJson): _*
        )
    }

  private implicit val publishMessageFormat = new RootJsonFormat[PublishMessage] {
    def read(json: JsValue): PublishMessage = {
      val data = json.asJsObject.fields("data").convertTo[String]
      val attributes = json.asJsObject.fields("attributes").convertTo[immutable.Map[String, String]]
      PublishMessage(data, attributes)
    }
    def write(m: PublishMessage): JsValue =
      JsObject(Seq("data" -> JsString(m.data)) ++ m.attributes.map(a => "attributes" -> a.toJson): _*)
  }

  private implicit val pubSubRequestFormat = new RootJsonFormat[PublishRequest] {
    def read(json: JsValue): PublishRequest =
      PublishRequest(json.asJsObject.fields("messages").convertTo[immutable.Seq[PublishMessage]])
    def write(pr: PublishRequest): JsValue = JsObject("messages" -> pr.messages.toJson)
  }
  private implicit val gcePubSubResponseFormat = new RootJsonFormat[PublishResponse] {
    def read(json: JsValue): PublishResponse =
      PublishResponse(json.asJsObject.fields("messageIds").convertTo[immutable.Seq[String]])
    def write(pr: PublishResponse): JsValue = JsObject("messageIds" -> pr.messageIds.toJson)
  }

  private implicit val receivedMessageFormat = new RootJsonFormat[ReceivedMessage] {
    def read(json: JsValue): ReceivedMessage =
      ReceivedMessage(json.asJsObject.fields("ackId").convertTo[String],
                      json.asJsObject.fields("message").convertTo[PubSubMessage])
    def write(rm: ReceivedMessage): JsValue =
      JsObject("ackId" -> rm.ackId.toJson, "message" -> rm.message.toJson)
  }
  private implicit val pubSubPullResponseFormat = new RootJsonFormat[PullResponse] {
    def read(json: JsValue): PullResponse =
      PullResponse(json.asJsObject.fields.get("receivedMessages").map(_.convertTo[immutable.Seq[ReceivedMessage]]))
    def write(pr: PullResponse): JsValue =
      pr.receivedMessages.map(rm => JsObject("receivedMessages" -> rm.toJson)).getOrElse(JsObject.empty)
  }

  private implicit val acknowledgeRequestFormat = new RootJsonFormat[AcknowledgeRequest] {
    def read(json: JsValue): AcknowledgeRequest =
      AcknowledgeRequest(json.asJsObject.fields("ackIds").convertTo[immutable.Seq[String]]: _*)
    def write(ar: AcknowledgeRequest): JsValue = JsObject("ackIds" -> ar.ackIds.toJson)
  }
  private implicit val pullRequestFormat = DefaultJsonProtocol.jsonFormat2(PullRequest)

  private def scheme: String = if (isEmulated) "http" else "https"

  def pull(project: String, subscription: String, returnImmediately: Boolean, maxMessages: Int)(
      implicit as: ActorSystem,
      materializer: Materializer
  ): Flow[(Done, Option[String]), PullResponse, NotUsed] = {
    import materializer.executionContext

    val url: Uri = Uri.from(
      scheme = scheme,
      host = PubSubGoogleApisHost,
      port = PubSubGoogleApisPort,
      path = s"/v1/projects/$project/subscriptions/$subscription:pull"
    )

    Flow[(Done, Option[String])]
      .mapAsync(1) {
        case (_, maybeAccessToken) =>
          for {
            request <- Marshal((HttpMethods.POST, url, PullRequest(returnImmediately, maxMessages)))
              .to[HttpRequest]
              .map(request => authorize(maybeAccessToken)(request))
            response <- Http().singleRequest(request)
            result <- readPullResponse(response)
          } yield result
      }
  }

  private def readPullResponse(response: HttpResponse)(
      implicit materializer: Materializer,
      executionContext: ExecutionContext
  ): Future[PullResponse] =
    response.status match {
      case StatusCodes.Success(_) if response.entity.contentType == ContentTypes.`application/json` =>
        Unmarshal(response).to[PullResponse]
      case status =>
        Unmarshal(response)
          .to[String]
          .map { entity =>
            throw new RuntimeException(s"Unexpected pull response. Code: [$status]. Entity: [$entity]")
          }
    }

  def acknowledge(project: String, subscription: String)(
      implicit as: ActorSystem,
      materializer: Materializer
  ): Flow[(AcknowledgeRequest, Option[String]), Done, NotUsed] = {
    import materializer.executionContext

    val url: Uri = Uri.from(
      scheme = scheme,
      host = PubSubGoogleApisHost,
      port = PubSubGoogleApisPort,
      path = s"/v1/projects/$project/subscriptions/$subscription:acknowledge"
    )

    Flow[(AcknowledgeRequest, Option[String])]
      .mapAsync(1) {
        case (request, maybeAccessToken) =>
          for {
            request <- Marshal((HttpMethods.POST, url, request))
              .to[HttpRequest]
              .map(request => authorize(maybeAccessToken)(request))
            response <- Http().singleRequest(request)
            result <- readAcknowledgeResponse(response)
          } yield result
      }
  }

  private def readAcknowledgeResponse(response: HttpResponse)(
      implicit materializer: Materializer,
      executionContext: ExecutionContext
  ): Future[Done] = {
    response.status match {
      case StatusCodes.Success(_) =>
        response.discardEntityBytes()
        Future.successful(Done)
      case status =>
        Unmarshal(response)
          .to[String]
          .map { entity =>
            throw new RuntimeException(
              s"Unexpected acknowledge response. Code [$status] Content-type [${response.entity.contentType}] Entity [$entity]"
            )
          }
    }
  }

  private def pool[T]()(
      implicit as: ActorSystem
  ): Flow[(HttpRequest, T), (Try[HttpResponse], T), Http.HostConnectionPool] =
    if (isEmulated) {
      Http().cachedHostConnectionPool[T](PubSubGoogleApisHost, PubSubGoogleApisPort)
    } else {
      Http().cachedHostConnectionPoolHttps[T](PubSubGoogleApisHost, PubSubGoogleApisPort)
    }

  def publish[T](project: String, topic: String, parallelism: Int)(
      implicit as: ActorSystem,
      materializer: Materializer
  ): FlowWithContext[(PublishRequest, Option[String]), T, immutable.Seq[String], T, NotUsed] = {
    import materializer.executionContext

    val url: Uri = s"/v1/projects/$project/topics/$topic:publish"
    FlowWithContext[(PublishRequest, Option[String]), T]
      .mapAsync(parallelism) {
        case (request, maybeAccessToken) =>
          Marshal((HttpMethods.POST, url, request)).to[HttpRequest].map(authorize(maybeAccessToken))
      }
      .via(pool())
      .mapAsync(parallelism) {
        case Success(response) =>
          response.status match {
            case StatusCodes.Success(_) if response.entity.contentType == ContentTypes.`application/json` =>
              Unmarshal(response.entity).to[PublishResponse].map(_.messageIds)
            case status =>
              Unmarshal(response)
                .to[String]
                .map { entity =>
                  throw new RuntimeException(
                    s"Unexpected publish response. Code [$status] Content-type [${response.entity.contentType}] Entity [$entity]"
                  )
                }
          }
        case Failure(NonFatal(ex)) =>
          Future.failed(ex)
      }
  }

  def accessToken[T](config: PubSubConfig)(
      implicit materializer: Materializer
  ): Flow[T, (T, Option[String]), NotUsed] =
    if (isEmulated) {
      Flow[T].map(request => (request, None: Option[String]))
    } else {
      Flow[T].mapAsync(1)(requestToken(config))
    }

  def accessTokenWithContext[T, C](config: PubSubConfig)(
      implicit materializer: Materializer
  ): FlowWithContext[T, C, (T, Option[String]), C, NotUsed] =
    if (isEmulated) {
      FlowWithContext[T, C].map(request => (request, None: Option[String]))
    } else {
      FlowWithContext[T, C].mapAsync(1)(requestToken(config))
    }

  private[this] def requestToken[T](
      config: PubSubConfig
  )(request: T)(implicit materializer: Materializer): Future[(T, Option[String])] = {
    import materializer.executionContext
    config.session.getToken().map(token => (request, Some(token): Option[String]))
  }

  private[this] def authorize(maybeAccessToken: Option[String])(request: HttpRequest): HttpRequest =
    maybeAccessToken.map(accessToken => request.addCredentials(OAuth2BearerToken(accessToken))).getOrElse(request)

}
