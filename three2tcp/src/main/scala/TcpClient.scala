import akka.actor._
import akka.io._
import java.net.InetSocketAddress

import akka.util.ByteString

import scala.concurrent.duration._
import AkkaMessages._


class TcpClientHandler(f: ActorRef, remote: InetSocketAddress, conn:Channel, initial0: ByteString) extends Actor {

  var initial = initial0 // this buffer-accumulator is to use before upstream connection is ready

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(remote)

  def receive = {
    case CommandFailed(_: Connect) =>
      println(s"TCP #$conn - connect failed")
      context.stop(self)

    case data: ByteString =>
      initial = initial ++ data
      println(s"Note: +initial for $conn; additional size=${data.size}, new size=${initial.size}")

    case c @ Connected(remote, local) =>
      println(s"TCPClient/Client handler connected from $local to $remote")
      val connection = sender()
      connection ! Register(self)
      connection ! Write(initial)
      context become {
        case data: ByteString =>
          connection ! Write(data)

        case CommandFailed(w: Write) =>
          // O/S buffer was full
          println(s"TCP #$conn - write failed")

        case Received(data) =>
          println(s"Recived @ #$conn data.size=${data.size}")
          f ! Msg(conn,data)

        case "close" =>
          context.parent ! TCPClose(conn) // ??? probably not working
          // TODO: free $conn id
          connection ! Close

        case _: ConnectionClosed =>
          println(s"TCP #$conn - connection closed")
          context stop(self)
      }
  }
}

class TcpClient(remote: InetSocketAddress) extends Actor with ActorLogging {

  var pool = scala.collection.parallel.mutable.ParMap[Channel,ActorRef]()

  def receive = {
    case Msg(ch, data: ByteString) =>
      println(s"TCPClient: Pipe2Tcp($ch, data.size=${data.size})")
      pool.get(ch) match {
        case Some(a: ActorRef) => a ! data
        case None => pool.put(ch,
          context.actorOf(Props(classOf[TcpClientHandler],
          context.parent, remote,ch,data), "tcp-client"+ch.connId))
      }

      /*
    case t @ Down(conn,data) =>
      println(s"forwarded @ #$conn data.size=${data.size}")
      context.parent ! t   // forward
       */

    case TCPClose(conn) =>
      println(s"TCPClient close #$conn")
      pool-=conn

  }

}
