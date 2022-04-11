import java.net.InetSocketAddress
import java.nio.charset.Charset

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import AkkaMessages._

class TcpServerHandler(client: ActorRef, conn: Channel) extends Actor with ActorLogging {
  import Tcp._
  def receive = {
    case Received(data) =>
      println(s"SimplisticHandler Received ${data.size} byte(s) for #$conn")
      client ! Msg(conn, data)

    case PeerClosed     =>
      println(s"closed $conn")
      context.parent ! TCPClose(conn)
      client ! TCPClose(conn)
      // TODO: signal into UP of free the chanel $conn
      context.stop(self)
  }
}


class TcpServer(listen: InetSocketAddress) extends Actor with ActorLogging {
  import Tcp._
  import context.system

  var pool = scala.collection.parallel.mutable.ParMap[Channel,ActorRef]()

  private var id : Long = 0;
  def newId() : Long = { id=id+1; id }

  println("Starting the server...")
  IO(Tcp) ! Bind(self, listen)

  def receive = {
    case b @ Bound(localAddress) =>
      println(s"TCPServer: Bound to $localAddress")
      context.parent ! b // ?

    case CommandFailed(_: Bind) =>
      println("CommandFailed")
      context.stop(self)

    case c @ Connected(remoteAddresses, localAddresses) =>
      val connId = newId()
      println(s"TCPServer: new connection accepted from $remoteAddresses, id=$connId")
      val conn = remoteAddresses.getPort
      val connection = sender()
      val handler =
        context.actorOf(Props(classOf[TcpServerHandler],
        context.parent, Channel(connId,conn)), "tcp_"+conn )

      val ch = Channel(connId,conn)
      pool.put( ch, connection )
      connection ! Register(handler)
      // TODO: signal to UP of assign the chanel $conn

    case msg @ TCPClose(conn) =>
      println(s"Remote close connection #$conn")
      pool-=conn
      //context.parent.forward(msg)

    case Msg(conn, data) =>
      pool.get(conn) match {
        case Some(c) => c ! Write(data)
        case None => println(s"Error! No connection/wrong channel #$conn")
      }

      /*
    case t @ Up(conn, data) =>
      println(s"forwarded @ #$conn data.size=${data.size}")
      context.parent ! t // forward
       */
  }
}
