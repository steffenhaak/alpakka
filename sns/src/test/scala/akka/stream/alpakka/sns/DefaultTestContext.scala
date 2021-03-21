/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.sns

import akka.actor.ActorSystem
import org.mockito.Mockito.reset
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import software.amazon.awssdk.services.sns.SnsAsyncClient

import scala.concurrent.Await
import scala.concurrent.duration._

trait DefaultTestContext extends BeforeAndAfterAll with BeforeAndAfterEach with MockitoSugar { this: Suite =>

  implicit protected val system: ActorSystem = ActorSystem()
  implicit protected val snsClient: SnsAsyncClient = mock[SnsAsyncClient]

  override protected def beforeEach(): Unit =
    reset(snsClient)

  override protected def afterAll(): Unit =
    Await.ready(system.terminate(), 5.seconds)

}
