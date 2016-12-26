package com.darienmt.kamonlogstash

import java.time.{Instant, ZonedDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.darienmt.kamonlogstash.MetricLogger.{Metric, Record, Tag}
import kamon.metric.Entity
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{Counter, Histogram}
import kamon.util.MilliTimestamp

object MetricLogger {

  case class Metric(
    from: ZonedDateTime,
    to: ZonedDateTime,
    entity: String,
    category: String,
    tags: List[Tag],
    keyName: String,
    records: List[Record]
  )

  case class Tag(name: String, value: String)
  case class Record(value: Long, occurrence: Long)

  def props(shipper: ActorRef): Props = Props(new MetricLogger(shipper))
}

object Conversions {

  import scala.language.implicitConversions

  implicit def convertToZonedDateTime(t: MilliTimestamp): ZonedDateTime = {
    val inst = Instant.ofEpochMilli(t.millis)
    ZonedDateTime.ofInstant(inst, ZonedDateTime.now().getZone)
  }

}

class MetricLogger(shipper: ActorRef) extends Actor with ActorLogging {

  import Conversions._

  def receive: Receive = {
    case tick: TickMetricSnapshot => shipper ! translate(tick).filter(_.records.nonEmpty)
  }

  def translate(tick: TickMetricSnapshot): Iterable[Metric] =
    for {
      (entity, snapshot) <- tick.metrics
      (metricKey, metric) <- snapshot.metrics
    } yield metric match {
      case h: Histogram.Snapshot => Metric (
        from = tick.from,
        to = tick.to,
        entity = entity.name,
        category = entity.category,
        tags = entity.tags.map( kv => Tag(kv._1, kv._2)).toList,
        keyName = metricKey.name,
        records = h.recordsIterator.map(r => Record(r.level, r.count)).toList
      )
      case c: Counter.Snapshot => Metric(
        from = tick.from,
        to = tick.to,
        entity = entity.name,
        category = entity.category,
        tags = entity.tags.map( kv => Tag(kv._1, kv._2)).toList,
        keyName = metricKey.name,
        records = List(Record(c.count,1))
      )
    }
}
