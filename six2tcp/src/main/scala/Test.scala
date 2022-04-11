import AkkaMessages.Channel
import akka.actor.ActorSystem
import akka.protobuf.ByteString
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source, Tcp}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import com.karasiq.proxy.server.{ProxyConnectionRequest, ProxyServer}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.util.{Failure, Success}

object Test extends App {

  val app = "Test"

  val config = ConfigFactory.parseString("akka.loglevel = DEBUG")
  implicit val system = ActorSystem(app, config)
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  println("Akka: "+system.name)

  val listen_host = "localhost"
  val listen_port = 1080

  val connections: Source[IncomingConnection, Future[ServerBinding]] =
    Tcp().bind( listen_host, listen_port ) // , options = List(KeepAlive(true), TcpNoDelay(true)) )

  connections.runForeach { connection: IncomingConnection =>
    val ch = Channel.create(connection.localAddress.getPort)

    println(s"New connection from: ${connection.remoteAddress} assigned to channel $ch")

    connection.handleWith(ProxyServer()).foreach {
      case (request, conn) ⇒
        println(s"Request $request via $conn")
        //runViaChain(connections, request, conn)

        val out = Tcp().outgoingConnection(request.address)
        //conn.join(out).run()
        //out.join(conn).run()
        ProxyServer.withSuccess(conn, request).join(out).run()
    }

  }
}
