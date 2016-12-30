package com.darienmt.kamonlogstash

import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import com.darienmt.kamonlogstash.LogstashWatcher._
import com.darienmt.kamonlogstash.MetricShipper.ShipperConfig

import scala.concurrent.duration.FiniteDuration

object LogstashWatcher {

  sealed trait LogstashWatcherMessage

  case object Connect extends LogstashWatcherMessage
  case class UseClient(client: ActorRef) extends LogstashWatcherMessage
  case object WaitForReconnection extends LogstashWatcherMessage
  case object Tick extends LogstashWatcherMessage
  case object ResetCount extends LogstashWatcherMessage

  def props(shipper: ActorRef, config: ShipperConfig): Props = Props(new LogstashWatcher(shipper, config))

  private def calculateDelay(
                                    restartCount: Int,
                                    minBackoff:   FiniteDuration,
                                    maxBackoff:   FiniteDuration,
                                    randomFactor: Double): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount >= 30) { // Duration overflow protection (> 100 years)
      maxBackoff
    } else {
      maxBackoff.min(minBackoff * math.pow(2, restartCount)) * rnd match {
        case f: FiniteDuration ⇒ f
        case _                 ⇒ maxBackoff
      }
    }
  }
}

class LogstashWatcher(shipper: ActorRef, config: ShipperConfig) extends Actor with ActorLogging {

  import context.dispatcher

  var retryCount = 0
  var autoResetCancellable: Option[Cancellable] = None

  val remote = new InetSocketAddress(config.address, config.port)

  def createClient(): ActorRef = {
    val client = context.actorOf(LogstashClient.props(remote, self))
    context watch client
  }
  def receive: Receive = {
    case Connect => {
      context become(waitingForConnection(createClient()), discardOld = true)
    }
  }

  def waitingForConnection(client: ActorRef): Receive = {
    case LogstashClient.Connected => {
      shipper ! UseClient(client)
      log.info(s"Connection to logstash at $remote")
      autoResetCancellable = Some(context.system.scheduler.scheduleOnce(config.retryAutoReset,self, ResetCount))
    }
    case Terminated(`client`) => {
      shipper ! WaitForReconnection
      context unwatch client
      autoResetCancellable.foreach(_.cancel())
      val delay = calculateDelay(retryCount,config.minBackoff, config.maxBackoff, config.randomFactor)
      log.error(s"Error connecting to logstash at $remote. Retry count: $retryCount. Next retry in $delay")
      retryCount = retryCount + 1
      context.system.scheduler.scheduleOnce(delay,self, Tick)
    }
    case ResetCount => {
      log.info(s"Connection retry re-set after ${config.retryAutoReset} without problems.")
      retryCount = 0
    }
    case Tick => context become(waitingForConnection(createClient()), discardOld = true)
  }
}
