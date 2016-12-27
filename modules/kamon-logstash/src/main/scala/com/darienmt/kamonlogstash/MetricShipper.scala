package com.darienmt.kamonlogstash

import java.net.InetSocketAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import akka.actor.{Actor, ActorLogging, Props, Stash}
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.util.ByteString
import com.darienmt.kamonlogstash.MetricShipper.ShipperConfig
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

class MetricShipper(config: ShipperConfig) extends Actor with ActorLogging with Stash{

  protected final val LOG_SEPARATOR = "\r\n"

  val supervisorProps = BackoffSupervisor.props(
    Backoff.onFailure(
      LogstashClient.props(new InetSocketAddress(config.address, config.port), self),
      childName = "client",
      minBackoff = config.minBackoff,
      maxBackoff = config.maxBackoff,
      randomFactor = config.randomFactor
    )
      .withAutoReset(config.retryAutoReset)
  )

  val logstashClient = context.actorOf(supervisorProps)

  implicit final val encodeZonedDatetime: Encoder[ZonedDateTime] = encodeZonedDateTime(ISO_OFFSET_DATE_TIME)

  def receive: Receive = waitToConnect

  def connected: Receive = {
    case l: List[MetricLogger.Metric] => logstashClient ! ByteString(l.map(_.asJson.noSpaces).mkString(LOG_SEPARATOR) + LOG_SEPARATOR,"UTF-8")
    case LogstashClient.ConnectionClosed => context become(waitToConnect, discardOld = true)
  }

  def waitToConnect: Receive = {
    case LogstashClient.Connected => {
      unstashAll()
      context become(connected, discardOld = true)
    }
    case l:List[MetricLogger.Metric] => stash()
    case LogstashClient.ConnectionFailed => {
      log.error(s"Connection to ${config.address}:${config.port} failed")
      logstashClient ! BackoffSupervisor.GetRestartCount
    }
    case BackoffSupervisor.RestartCount(count) => log.error("Reconnection count " + count)
  }
}
