import java.net.InetSocketAddress

import AkkaMessages.{Channel, Msg}
import CryptoBidiFlow.blockSize
import akka.actor.{ActorSystem, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import com.typesafe.config.ConfigFactory

import scala.util.Try
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import com.mfglabs.stream.FlowExt

object Main extends App {

  val app = "tcp2four"

  println(s"Hi\t$app [listen_host [listen_port [remote_host [remote_port]]]]")
  val def_listen_host = "localhost"
  val def_listen_port = 8008
  val def_remote_host = "127.0.0.1"
  val def_remote_port = 8009
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

    /*
    system.actorOf(Props(classOf[TcpClient],
      new InetSocketAddress(listen_host, listen_port),
      remote_host, remote_port
    ), "one-client")
    */
    // [===
    println("Creating client handler flow source...")
    // see https://github.com/akka/akka/blob/master/akka-stream-typed/src/test/scala/docs/akka/stream/typed/ActorSourceSinkExample.scala
    // http://loicdescotte.github.io/posts/play-akka-streams-twitter/
    val (actorConsumer, publisher) =
    Source.actorRef[Msg](16, // 1024,
      // [ERROR] [three2tcp-akka.actor.default-dispatcher-8] [akka://three2tcp/user/one-server]
      // requirement failed: Backpressure overflowStrategy not supported
      // OverflowStrategy.backpressure
      OverflowStrategy.fail
    ).toMat(Sink.asPublisher(false))(Keep.both).run()

    println("Creating client handler flow actor integration...")
    def toMsg = Flow[ByteString]
      //.sliding(blockSize,blockSize) // block!!

      //.statefulMapConcat(()=>s=>s.grouped(blockSize).toList)
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
    def actorFlow = Flow.fromSinkAndSource(
      Sink.actorRef[Msg](
        system.actorOf(Props(classOf[TcpServer],
          actorConsumer,
          new InetSocketAddress(listen_host, listen_port) ), "tcp-server"),
        Msg.empty),
      Source.fromPublisher(publisher)
    )

    println("Creating client handler flow with ciphers...")
    val flow = CryptoBidiFlow.bidiFlow join toMsg.via(actorFlow).map
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

    println("Creating client handler flow is ready to use...")
    val connection = Tcp().outgoingConnection(remote_host, remote_port)
    connection.join(flow).run()
    //connection.to(Sink.foreach(println))
    //val ff = Flow.fromFunction[ByteString,ByteString](bs=>ByteString(bs.utf8String.toUpperCase))
    //connection.join(ff).run()

    println("Client handler flow connected...")
    // ===]

    println(s"$app initialization completed.")
  }
}
