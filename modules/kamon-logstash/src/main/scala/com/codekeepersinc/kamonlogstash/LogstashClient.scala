package com.codekeepersinc.kamonlogstash

import java.net.InetSocketAddress

import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import LogstashClient.CloseConnection

object LogstashClient {

  sealed trait LogstashClientMessage

  case object Connect extends LogstashClientMessage
  case object Connected extends LogstashClientMessage
  case object CloseConnection extends LogstashClientMessage

  def props(remote: InetSocketAddress, manager: ActorRef): Props = Props(new LogstashClient(remote, manager))

}

class LogstashClient(remote: InetSocketAddress, manager: ActorRef) extends Actor {

  import Tcp._
  import context.system

  def receive: Receive = {
    case Connect => IO(Tcp) ! Connect(remote)
    case CommandFailed(_: Connect) => context stop self

    case c @ Connected(remote, local) =>
      manager ! LogstashClient.Connected
      val connection = sender()
      connection ! Register(self)
      context watch connection
      context become receiveOnConnected(connection)
  }

  def receiveOnConnected(connection: ActorRef): Receive = {
    case data: ByteString => connection ! Write(data)

    case CommandFailed(w: Write) => context stop self

    case CloseConnection => connection ! Close

    case _: ConnectionClosed => context stop self

  }

  override def preStart(): Unit = self ! Connect
}
