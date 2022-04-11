import java.net.InetSocketAddress

import AkkaMessages.{Channel, Msg}
import CryptoBidiFlow.blockSize
import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import com.karasiq.proxy.server.ProxyServer
import com.mfglabs.stream.FlowExt
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object Main extends App {

  val app = "five2tcp"

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
    implicit val materializer = ActorMaterializer()

    val connections: Source[IncomingConnection, Future[ServerBinding]] =
      Tcp().bind(listen_host, listen_port)

    connections.runForeach { connection =>
      println(s"New connection from: ${connection.remoteAddress}")

      // [===
      println("Creating server handler flow source...")
      // see https://github.com/akka/akka/blob/master/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala
      // http://loicdescotte.github.io/posts/play-akka-streams-twitter/
      val (actorConsumer, publisher) =
      Source.actorRef[Msg](16, // 1024,
        // [ERROR] [three2tcp-akka.actor.default-dispatcher-8] [akka://three2tcp/user/one-server]
        // requirement failed: Backpressure overflowStrategy not supported
        // OverflowStrategy.backpressure
        OverflowStrategy.fail
      ).toMat(Sink.asPublisher(false))(Keep.both).run()

      println("Creating server handler flow actor integration...")
      def toMsg = Flow[ByteString]
        //.sliding(blockSize,blockSize) // block!!
        .via(FlowExt.rechunkByteStringBySize(blockSize))
        .statefulMapConcat(()=>{
          var ch = Channel.empty
          var s = 0
          println("initilized")
          data =>
            println("flow got data.size="+data.size)
            assert(data.size == blockSize)
            if (s > 0) {
              val ready = data.take(s)
              s -= ready.size
              List( Msg(ch, ready) )
            }
            else {
              val msgInfo = MsgInfo(data.toArray)
              println(s"got msgInfo: $msgInfo")
              s = msgInfo.dataSize
              ch = msgInfo.channel
              List.empty
            }
        })
      /*
      def actorFlow = Flow.fromSinkAndSource(
        Sink.actorRef[Msg](system.actorOf(Props(classOf[TcpClient],
          actorConsumer,
          new InetSocketAddress(remote_host, remote_port) ) ),
          Msg.empty
        ),
        Source.fromPublisher(publisher)
      )
      */
      def fromMsg = Flow.fromFunction[Msg,ByteString]
      {
        case Msg(ch,data) =>
          println(s"Server: Down($ch, data.size=${data.size}) "+
            data.map(_.formatted("0x%02x")).mkString(", ") )

          val stream = 0 // TODO: you can do partitioning to spread data into maxStreams parallel streams
          // val stream = ch.connId % maxStreams

          // 1. Service information
          val rawMsgInfo = MsgInfo(ch,data.size).toBytes()

          // 2. User data itself
          val len = data.size + ( blockSize - data.size % blockSize ) % blockSize
          val len1 = ((data.size+blockSize-1)/blockSize)*blockSize
          assert(len==len1)
          val rawData = data.padTo(len,0.toByte).toArray

          ByteString(rawMsgInfo)++ByteString(rawData)
      }

      def socks5Flow: Flow[Msg,Msg,NotUsed] = { FlowPartitioner.apply[Msg,ByteString,Channel](
        (msg: Msg, _: Int) => msg.ch,
        (i:Int) => Flow.fromFunction{m:Msg=>m.data}.via(ProxyServer().buffer(8, OverflowStrategy.backpressure))
      ).map[Msg]{ case (ch:Channel, bs:ByteString) => Msg(ch,bs) }}

      println("Creating server handler flow with ciphers...")
      def serverFlow = toMsg via socks5Flow via fromMsg

      val handler = CryptoBidiFlow.bidiFlow join serverFlow

      // was: CryptoBidiFlow.bidiFlow.join(actorFlow)

      println("Creating server/connection handler flow is ready to use...")
      connection handleWith handler
      println("Server handler flow connected...")

      // ===]

    }.failed.map(_.getMessage).foreach( println )

    println(s"$app initialization completed.")
  }
}
