package com.darienmt.kamonlogstash

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.event.Logging
import com.darienmt.kamonlogstash.MetricShipper.ShipperConfig
import com.typesafe.config.Config
import kamon.Kamon
import kamon.util.ConfigTools.Syntax

import scala.collection.JavaConverters._
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

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

  private val appName = logstashConfig.getString("appname")
  private val hostName = logstashConfig.getString("hostname")

  private val shipperConfig = ShipperConfig(
    address = logstashConfig.getString("address"),
    port = logstashConfig.getInt("port"),
    minBackoff = FiniteDuration(logstashConfig.getDuration("retry.minBackoff").toMillis, MILLISECONDS),
    maxBackoff = FiniteDuration(logstashConfig.getDuration("retry.maxBackoff").toMillis, MILLISECONDS),
    randomFactor = logstashConfig.getDouble("retry.randomFactor"),
    retryAutoReset = FiniteDuration(logstashConfig.getDuration("retry.retryAutoReset").toMillis, MILLISECONDS)
  )

  private val shipper = system.actorOf(MetricShipper.props(shipperConfig), "metric-shipper")
  private val logger = system.actorOf(MetricLogger.props(appName, hostName, shipper), "subscription-logger")


  private val subscriptions: Config = logstashConfig.getConfig("subscriptions")

  subscriptions.firstLevelKeys.foreach { subscriptionCategory =>
    subscriptions.getStringList(subscriptionCategory).asScala.foreach { pattern =>
      metricsExtension.subscribe(subscriptionCategory, pattern, logger, permanently = true)
    }
  }
}
