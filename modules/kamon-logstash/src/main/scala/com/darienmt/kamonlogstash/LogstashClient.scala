package com.darienmt.kamonlogstash

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import com.darienmt.kamonlogstash.LogstashClient.{CloseConnection, ConnectionClosed, ConnectionFailed, WriteFailed}

object LogstashClient {

  sealed trait LogstashClientMessages

  case object Connected extends LogstashClientMessages
  case object ConnectionFailed extends LogstashClientMessages
  case object WriteFailed extends LogstashClientMessages
  case object CloseConnection extends LogstashClientMessages
  case object ConnectionClosed extends LogstashClientMessages

  def props(remote: InetSocketAddress, manager: ActorRef): Props = Props(new LogstashClient(remote, manager))

}

class LogstashClient(remote: InetSocketAddress, manager: ActorRef) extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(remote)

  def receive: Receive = {
    case CommandFailed(_: Connect) =>
      manager ! ConnectionFailed
      context stop self

    case c @ Connected(remote, local) =>
      manager ! LogstashClient.Connected
      val connection = sender()
      connection ! Register(self)
      context watch connection
      context become receiveOnConnected(connection)
  }

  def receiveOnConnected(connection: ActorRef): Receive = {
    case data: ByteString =>
      connection ! Write(data)

    case CommandFailed(w: Write) =>
      // O/S buffer was full
      manager ! WriteFailed

    case Received(data) =>
      manager ! data

    case CloseConnection =>
      connection ! Close

    case _: ConnectionClosed =>
      manager ! ConnectionClosed
      context stop self
  }
}
