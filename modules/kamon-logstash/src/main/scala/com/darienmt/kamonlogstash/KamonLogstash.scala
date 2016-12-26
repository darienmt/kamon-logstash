package com.darienmt.kamonlogstash

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.event.Logging
import com.typesafe.config.Config
import kamon.Kamon
import kamon.util.ConfigTools.Syntax

import scala.collection.JavaConverters._

object KamonLogstash extends ExtensionId[KamonLogstashExtension] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): KamonLogstashExtension = new KamonLogstashExtension(system)

  override def lookup(): ExtensionId[_ <: Extension] = KamonLogstash
}

class KamonLogstashExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  val log = Logging(system, classOf[KamonLogstashExtension])
  log.info("Starting the Kamon Logstash extension")

  private val metricsExtension = Kamon.metrics

  private val config = system.settings.config
  private val logstashConfig = config.getConfig("kamon.logstash")

  private val lsAddress = logstashConfig.getString("address")
  private val lsPort = logstashConfig.getInt("port")
  private val shipper = system.actorOf(MetricShipper.props(lsAddress, lsPort), "metric-shipper")
  private val logger = system.actorOf(MetricLogger.props(shipper), "subscription-logger")

  private val subscriptions: Config = logstashConfig.getConfig("subscriptions")

  subscriptions.firstLevelKeys.foreach { subscriptionCategory =>
    subscriptions.getStringList(subscriptionCategory).asScala.foreach { pattern =>
      metricsExtension.subscribe(subscriptionCategory, pattern, logger, permanently = true)
    }
  }
}
