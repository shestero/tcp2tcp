import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

import scala.util.{Try, Success, Failure}

object Main extends App {

  val app = "three2tcp"

  // NB note https://github.com/scopt/scopt
  println(s"Hi\t$app [listen_host [listen_port [remote_host [remote_port]]]]")
  val def_listen_host = "localhost"
  val def_listen_port = 8009
  val def_remote_host = "localhost"
  val def_remote_port = 1080
  println(s"Where defaults are: $def_listen_host, $def_listen_port and $def_remote_host, $def_remote_port")

  val argmap = ((Stream from 1) zip args).toMap
  Try {
    (
      argmap.getOrElse(1, def_listen_host),
      argmap.get(2).map(_.toInt).getOrElse(def_listen_port),
      argmap.getOrElse(3, def_remote_host),
      argmap.get(4).map(_.toInt).getOrElse(def_remote_port)
    )
  }.map{ params =>
    println(s"So using: $params")
    run _ tupled params
  }.failed.map { e =>
    println(e.getMessage)
  }

  def run(listen_host: String, listen_port: Int, remote_host: String, remote_port:Int): Unit =
  {
    val config = ConfigFactory.parseString("akka.loglevel = DEBUG")
    implicit val system = ActorSystem(app, config)
    implicit val ec = system.dispatcher
    // implicit val materializer = ActorMaterializer()

    println("Creating server actor...")
    system.actorOf(Props(classOf[TcpServer],
      new InetSocketAddress(listen_host, listen_port),
      new InetSocketAddress(remote_host, remote_port)
    ), "one-server")

    println(s"$app initialization completed.")
  }
}
