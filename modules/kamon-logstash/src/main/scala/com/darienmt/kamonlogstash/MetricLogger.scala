package com.darienmt.kamonlogstash

import java.time.{Instant, ZonedDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.darienmt.kamonlogstash.MetricLogger.{AkkaData, Metric, Record, Tag}
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{Counter, Histogram}
import kamon.util.MilliTimestamp

object MetricLogger {

  case class Metric(
    appName: String,
    hostName: String,
    from: ZonedDateTime,
    to: ZonedDateTime,
    category: String,
    entity: String,
    akkaData: Option[AkkaData],
    tags: List[Tag],
    keyName: String,
    records: List[Record]
  )

  case class AkkaData(actorSystem: String, topParent: String, path: String)

  case class Tag(name: String, value: String)
  case class Record(value: Long, occurrence: Long)

  def props(appName: String, hostName: String, shipper: ActorRef): Props = Props(new MetricLogger(appName, hostName, shipper))
}

object Conversions {

  import scala.language.implicitConversions

  implicit def convertToZonedDateTime(t: MilliTimestamp): ZonedDateTime = {
    val inst = Instant.ofEpochMilli(t.millis)
    ZonedDateTime.ofInstant(inst, ZonedDateTime.now().getZone)
  }

}

class MetricLogger(appName: String, hostName: String, shipper: ActorRef) extends Actor with ActorLogging {

  import Conversions._

  def receive: Receive = {
    case tick: TickMetricSnapshot => shipper ! translate(tick).filter(_.records.nonEmpty)
  }

  def translate(tick: TickMetricSnapshot): Iterable[Metric] =
    for {
      (entity, snapshot) <- tick.metrics
      (metricKey, metric) <- snapshot.metrics
    } yield
      Metric (
        appName = appName,
        hostName = hostName,
        from = tick.from,
        to = tick.to,
        category = entity.category,
        entity = entity.name,
        akkaData = parseEntity(entity.category, entity.name),
        tags = entity.tags.map( kv => Tag(kv._1, kv._2)).toList,
        keyName = metricKey.name,
        records = metric match {
          case h: Histogram.Snapshot => h.recordsIterator.map(r => Record(r.level, r.count)).toList
          case c: Counter.Snapshot => List(Record(c.count,1))
        }
      )

  def parseEntity(category: String, name: String):Option[AkkaData] =
    if ( !category.startsWith("akka-") ) {
      None
    } else {
      val actorSystem :: topParent :: rest = name.split("/").toList
      Some(AkkaData(actorSystem, topParent, rest.mkString("/")))
    }
}
