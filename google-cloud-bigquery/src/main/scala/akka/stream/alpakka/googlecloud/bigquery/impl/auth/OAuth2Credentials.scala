/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.bigquery.impl.auth

import akka.actor.{Actor, ActorContext, ActorRef}
import akka.actor.Status.Failure
import akka.annotation.InternalApi
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.pattern.pipe
import akka.stream.alpakka.googlecloud.bigquery.BigQuerySettings
import akka.stream.alpakka.googlecloud.bigquery.impl.auth.OAuth2Credentials.TokenRequest

import java.time.Clock
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}

@InternalApi
private[auth] object OAuth2Credentials {
  final case class TokenRequest(promise: Promise[OAuth2BearerToken], settings: BigQuerySettings)
}

private[auth] final class OAuth2CredentialsProvider(val projectId: String, credentials: ActorRef)
    extends CredentialsProvider {
  override def getToken()(implicit ec: ExecutionContext, settings: BigQuerySettings): Future[OAuth2BearerToken] = {
    val token = Promise[OAuth2BearerToken]()
    credentials ! TokenRequest(token, settings)
    token.future
  }
}

@InternalApi
private[auth] abstract class OAuth2Credentials extends Actor {

  protected final implicit def clock = Clock.systemUTC()

  private var refreshing = false
  private var cachedToken: Option[AccessToken] = None
  private val openPromises = mutable.ArrayBuffer[Promise[OAuth2BearerToken]]()

  override final def receive: Receive = {

    case TokenRequest(promise, settings) if !refreshing =>
      val refresh = cachedToken.forall(_.expiresSoon())
      if (refresh) {

        refreshing = true
        cachedToken = None

        openPromises += promise

        import context.dispatcher
        getAccessToken()(context, settings).pipeTo(self)

      } else {

        cachedToken.foreach {
          case AccessToken(token, _) =>
            promise.success(OAuth2BearerToken(token))
        }

      }

    case TokenRequest(promise, _) if refreshing =>
      openPromises += promise

    case accessToken @ AccessToken(token, _) =>
      refreshing = false
      cachedToken = Some(accessToken)

      openPromises.foreach(_.success(OAuth2BearerToken(token)))
      openPromises.clear()

    case Failure(cause) =>
      refreshing = false
      cachedToken = None

      openPromises.foreach(_.failure(cause))
      openPromises.clear()
  }

  protected def getAccessToken()(implicit ctx: ActorContext, settings: BigQuerySettings): Future[AccessToken]
}
