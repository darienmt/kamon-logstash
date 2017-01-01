package com.darienmt.kamonlogstash

import java.time.{Instant, ZonedDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.darienmt.kamonlogstash.MetricLogger.{AkkaData, Metric, Tag}
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{Counter, Histogram, InstrumentSnapshot}
import kamon.metric.{Entity, EntitySnapshot, MetricKey}
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
    unitLabel: String,
    value: Long
  )

  case class AkkaData(actorSystem: String, topParent: String, path: String)

  case class Tag(name: String, value: String)

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
    case tick: TickMetricSnapshot => shipper ! translate(tick)
  }

  def translate(tick: TickMetricSnapshot): Iterable[Metric] =
    for {
      (entity, snapshot, akkaData) <- tick.metrics.map(parseEntity)
      (metricKey, instrumentSnapshot) <- snapshot.metrics
      metric <- getMetrics(tick, entity, akkaData, metricKey, instrumentSnapshot)
    } yield metric


  def getMetrics(tick: TickMetricSnapshot, entity: Entity, akkaData: Option[AkkaData], metricKey: MetricKey, instruments: InstrumentSnapshot): List[Metric] =
    (instruments match {
      case h: Histogram.Snapshot => h.recordsIterator.flatMap(r => (1 to r.count.toInt).map(_ => r.level)).toList
      case c: Counter.Snapshot => List(c.count)
    }).map( value => Metric (
        appName = appName,
        hostName = hostName,
        from = tick.from,
        to = tick.to,
        category = entity.category,
        entity = entity.name,
        akkaData = akkaData,
        tags = entity.tags.map( kv => Tag(kv._1, kv._2)).toList,
        keyName = metricKey.name,
        unitLabel = metricKey.unitOfMeasurement.label,
        value = value
      ))

  def parseEntity(entitySnapshot: (Entity, EntitySnapshot)):(Entity, EntitySnapshot, Option[AkkaData]) = {
    val (entity, snapShot) = entitySnapshot
    val akkaData = if ( !entity.category.startsWith("akka-") ) {
                    None
                  } else {
                    val actorSystem :: topParent :: rest = entity.name.split("/").toList
                    Some(AkkaData(actorSystem, topParent, rest.mkString("/")))
                  }
    (entity, snapShot, akkaData)
  }

}
