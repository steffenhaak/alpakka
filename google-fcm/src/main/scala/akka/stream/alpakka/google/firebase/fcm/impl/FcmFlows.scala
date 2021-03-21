/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.google.firebase.fcm.impl
import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.scaladsl.Http
import akka.stream.alpakka.google.firebase.fcm._
import akka.stream.scaladsl.Flow

import scala.concurrent.Future

/**
 * INTERNAL API
 */
@InternalApi
private[fcm] object FcmFlows {

  private[fcm] def fcmWithData[T](conf: FcmSettings,
                                  sender: FcmSender): Flow[(FcmNotification, T), (FcmResponse, T), NotUsed] =
    Flow
      .fromMaterializer { (materializer, _) =>
        import materializer.executionContext
        val http = Http()(materializer.system)
        val session: GoogleSession = new GoogleSession(conf.clientEmail,
                                                       conf.privateKey,
                                                       new GoogleTokenApi(http, materializer.system, conf.forwardProxy))
        Flow[(FcmNotification, T)]
          .mapAsync(conf.maxConcurrentConnections)(
            in =>
              session.getToken()(materializer).flatMap { token =>
                sender
                  .send(conf, token, http, FcmSend(conf.isTest, in._1), materializer.system)(materializer)
                  .zip(Future.successful(in._2))
              }
          )
      }
      .mapMaterializedValue(_ => NotUsed)

  private[fcm] def fcm(conf: FcmSettings, sender: FcmSender): Flow[FcmNotification, FcmResponse, NotUsed] =
    Flow
      .fromMaterializer { (materializer, _) =>
        import materializer.executionContext
        val http = Http()(materializer.system)
        val session: GoogleSession = new GoogleSession(conf.clientEmail,
                                                       conf.privateKey,
                                                       new GoogleTokenApi(http, materializer.system, conf.forwardProxy))
        val sender: FcmSender = new FcmSender()
        Flow[FcmNotification]
          .mapAsync(conf.maxConcurrentConnections)(
            in =>
              session.getToken()(materializer).flatMap { token =>
                sender.send(conf, token, http, FcmSend(conf.isTest, in), materializer.system)(materializer)
              }
          )
      }
      .mapMaterializedValue(_ => NotUsed)
}
