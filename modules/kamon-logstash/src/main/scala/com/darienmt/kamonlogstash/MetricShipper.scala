package com.darienmt.kamonlogstash

import java.net.InetSocketAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import akka.actor.{Actor, ActorLogging, Props, Stash}
import akka.util.ByteString
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.java8.time.encodeZonedDateTime

object MetricShipper {

  def props(lsAddress: String, lsPort: Int): Props = Props(new MetricShipper(lsAddress, lsPort))
}

class MetricShipper(lsAddress: String, lsPort: Int) extends Actor with ActorLogging with Stash{

  val logstashClient = context.actorOf(LogstashClient.props(new InetSocketAddress(lsAddress, lsPort), self))

  implicit final val encodeZonedDatetime: Encoder[ZonedDateTime] = encodeZonedDateTime(ISO_OFFSET_DATE_TIME)

  def receive: Receive = {
    case LogstashClient.Connected => {
      unstashAll()
      context become(afterConnectionStablished, discardOld = true)
    }
    case LogstashClient.ConnectionFailed => log.error(s"Connection to $lsAddress:$lsPort failed")
    case l:List[MetricLogger.Metric] => stash()
    case msg => log.error("Error message " + msg.toString)
  }

  def afterConnectionStablished: Receive = {
    case l: List[MetricLogger.Metric] => logstashClient ! ByteString(l.map(_.asJson.noSpaces).mkString("\r"),"UTF-8")
    case msg => log.error("Error message after Connected " + msg.toString)
  }
}
