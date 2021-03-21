/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.jms

import akka.actor.ActorSystem
import akka.stream.alpakka.testkit.scaladsl.LogCapturing
import akka.testkit.TestKit
import javax.jms._
import jmstestkit.JmsBroker
import org.mockito.ArgumentMatchers.{any, anyBoolean, anyInt}
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

abstract class JmsSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with Eventually
    with MockitoSugar
    with LogCapturing {

  implicit val system = ActorSystem(this.getClass.getSimpleName)

  val consumerConfig = system.settings.config.getConfig(JmsConsumerSettings.configPath)
  val producerConfig = system.settings.config.getConfig(JmsProducerSettings.configPath)
  val browseConfig = system.settings.config.getConfig(JmsBrowseSettings.configPath)

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  def withConnectionFactory()(test: ConnectionFactory => Unit): Unit =
    withServer() { server =>
      test(server.createConnectionFactory)
    }

  def withServer()(test: JmsBroker => Unit): Unit = {
    val jmsBroker = JmsBroker()
    try {
      test(jmsBroker)
      Thread.sleep(500)
    } finally {
      if (jmsBroker.isStarted) {
        jmsBroker.stop()
      }
    }
  }

  def withMockedProducer(test: ProducerMock => Unit): Unit = test(ProducerMock())

  case class ProducerMock(factory: ConnectionFactory = mock[ConnectionFactory],
                          connection: Connection = mock[Connection],
                          session: Session = mock[Session],
                          producer: MessageProducer = mock[MessageProducer],
                          queue: javax.jms.Queue = mock[javax.jms.Queue]) {
    when(factory.createConnection()).thenReturn(connection)
    when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session)
    when(session.createProducer(any[javax.jms.Destination])).thenReturn(producer)
    when(session.createQueue(any[String])).thenReturn(queue)
  }

  case class ConsumerMock(factory: ConnectionFactory = mock[ConnectionFactory],
                          connection: Connection = mock[Connection],
                          session: Session = mock[Session],
                          consumer: MessageConsumer = mock[MessageConsumer],
                          queue: javax.jms.Queue = mock[javax.jms.Queue]) {
    when(factory.createConnection()).thenReturn(connection)
    when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session)
    when(session.createConsumer(any[javax.jms.Destination])).thenReturn(consumer)
    when(session.createQueue(any[String])).thenReturn(queue)
  }

  def withMockedConsumer(test: ConsumerMock => Unit): Unit = test(ConsumerMock())

}
