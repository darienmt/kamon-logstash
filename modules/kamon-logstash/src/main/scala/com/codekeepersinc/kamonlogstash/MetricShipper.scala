package com.codekeepersinc.kamonlogstash

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Stash }
import akka.util.ByteString
import MetricShipper.ShipperConfig
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.java8.time.encodeZonedDateTime

import scala.concurrent.duration.FiniteDuration

object MetricShipper {

  case class ShipperConfig(
    address: String,
    port: Int,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    retryAutoReset: FiniteDuration
  )

  def props(config: ShipperConfig): Props = Props(new MetricShipper(config))
}

class MetricShipper(config: ShipperConfig) extends Actor with ActorLogging with Stash {

  protected final val LOG_SEPARATOR = "\r\n"

  val watcher = context.actorOf(LogstashWatcher.props(self, config), "watcher")

  implicit final val encodeZonedDatetime: Encoder[ZonedDateTime] = encodeZonedDateTime(ISO_OFFSET_DATE_TIME)

  def receive: Receive = waitForClient

  def connected(client: ActorRef): Receive = {
    case l: List[MetricLogger.Metric] => client ! ByteString(l.map(_.asJson.noSpaces).mkString(LOG_SEPARATOR) + LOG_SEPARATOR, "UTF-8")
    case LogstashWatcher.WaitForReconnection => context become (waitForClient, discardOld = true)
  }

  def waitForClient: Receive = {
    case LogstashWatcher.UseClient(client) => {
      unstashAll()
      context become (connected(client), discardOld = true)
    }
    case l: List[MetricLogger.Metric] => stash()
  }

  override def preStart(): Unit = watcher ! LogstashWatcher.Connect
}
