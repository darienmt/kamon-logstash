package com.darienmt.kamonlogstash

import java.time.{ Instant, ZonedDateTime }

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import com.darienmt.kamonlogstash.MetricLogger.{ CounterMetric, HistogramMetric, Metric, Record }
import kamon.metric.{ Entity, EntitySnapshot, MetricKey }
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{ Counter, Histogram, InstrumentSnapshot }
import kamon.util.MilliTimestamp

object MetricLogger {

  sealed trait Metric {
    val from: ZonedDateTime
    val to: ZonedDateTime
    val entity: Entity
    val keyName: String
  }
  case class HistogramMetric(
    from: ZonedDateTime,
    to: ZonedDateTime,
    entity: Entity,
    keyName: String,
    records: List[Record]
  ) extends Metric

  case class CounterMetric(
    from: ZonedDateTime,
    to: ZonedDateTime,
    entity: Entity,
    keyName: String,
    value: Long
  ) extends Metric

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
    case tick: TickMetricSnapshot => shipper ! translate(tick)
      .filter {
        case h: HistogramMetric => h.records.nonEmpty
        case _ => true
      }
  }

  def translate(tick: TickMetricSnapshot): Iterable[Metric] =
    for {
      (entity, snapshot) <- tick.metrics
      (metricKey, metric) <- snapshot.metrics
    } yield metric match {
      case h: Histogram.Snapshot => HistogramMetric(
        from = tick.from,
        to = tick.to,
        entity = entity,
        keyName = metricKey.name,
        records = h.recordsIterator.map(r => Record(r.level, r.count)).toList
      )
      case c: Counter.Snapshot => CounterMetric(
        from = tick.from,
        to = tick.to,
        entity = entity,
        keyName = metricKey.name,
        value = c.count
      )
    }
}
