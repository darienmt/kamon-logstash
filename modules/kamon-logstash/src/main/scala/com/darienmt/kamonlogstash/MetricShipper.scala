package com.darienmt.kamonlogstash

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import akka.actor.{Actor, ActorLogging, Props}
import com.darienmt.kamonlogstash.MetricLogger.{CounterMetric, HistogramMetric}
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.java8.time.encodeZonedDateTime

object MetricShipper {

  def props(): Props = Props(new MetricShipper())
}

class MetricShipper extends Actor with ActorLogging {

  implicit final val encodeZonedDatetime: Encoder[ZonedDateTime] = encodeZonedDateTime(ISO_OFFSET_DATE_TIME)

  def receive: Receive = {
    case l: List[MetricLogger.Metric] => l.map {
      case h: HistogramMetric => h.asJson.noSpaces
      case c: CounterMetric => c.asJson.noSpaces
    }.foreach(log.error)
  }
}
