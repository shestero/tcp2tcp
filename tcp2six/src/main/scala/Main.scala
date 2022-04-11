import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.TimeoutException

import AkkaMessages.{Channel, Msg}
import CryptoBidiFlow.blockSize
import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.coding._
import akka.http.scaladsl.marshalling.{Marshaller, Marshalling, ToResponseMarshaller}
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.HttpResponse
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import com.typesafe.config.ConfigFactory

import scala.util.{Failure, Success, Try}
import akka.stream.scaladsl.{BidiFlow, BroadcastHub, Flow, Keep, MergeHub, Sink, Source, Tcp}
import akka.util.ByteString
import org.reactivestreams
import org.reactivestreams.{Publisher, Subscription}
//import com.mfglabs.stream.FlowExt

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpEntity, ContentTypes }
import akka.http.scaladsl.server.Directives._

/*
import cats._
import cats.instances.int._ // For monoid instances for `Int`
import cats.instances.tuple._  // for Monoid instance for `Tuple2`
import cats.Monoid.combineAll
//import cats.Monoid.combine
//import cats.implicits._ // cause error
*/

import scala.concurrent.duration._
import scala.concurrent.Future

object Main extends App {

  val app = "tcp2six"

  println(s"Hi\t$app [listen_host [listen_port [remote_host [remote_port]]]]")
  val def_listen_host = "localhost"
  val def_listen_port = 8008
  val def_remote_host = "127.0.0.1"
  val def_remote_port = 8009
  val http_host = "localhost"
  val http_port = 8088

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


  protected def flowPartitioner[I,O,P](partitioner: I=>P, flower: P=>Flow[I,O,NotUsed]): Flow[I,O,NotUsed] =
  {
    val m = scala.collection.mutable.Map.empty[P, Flow[I,O,NotUsed]]
    Flow[I].map(e=>partitioner(e)->e).splitWhen( p=> !m.contains(p._1) ).flatMapConcat {
      case (partition, elem) => Source.single(elem).via( m.getOrElseUpdate( partition, flower(partition) ) )
    }.mergeSubstreams
  }

  def toLongMsg = Flow[ByteString]
    // .sliding(blockSize,blockSize) // block!!
    // .via(FlowExt.rechunkByteStringBySize(blockSize))

    // working:
    //.mapConcat( _.grouped(blockSize).toIterable.asInstanceOf[scala.collection.immutable.Iterable[ByteString]] )
    .mapConcat(_.toSeq).grouped(blockSize).map(_.map(b=>ByteString(b)).foldLeft(ByteString())(_++_))

    .statefulMapConcat(()=>{
      var s = 0
      var ch = Channel.empty
      var acc = ByteString()

      data =>
        if (data.size != blockSize) println("flow got data.size="+data.size)
        assert(data.size == blockSize)
        assert(s>=0)
        if (s <= 0) {
          val msgInfo = MsgInfo(data.toArray)
          println(s"got msgInfo: $msgInfo")
          s = msgInfo.dataSize
          ch = msgInfo.channel
          acc = ByteString()
          List.empty
        }
        else {
          val ready = data.take(s)
          s -= ready.size
          acc ++= ready
          if (s<=0)
            List( Msg(ch, acc) )
          else
            List.empty
        }
    })

  def fromMsg: Function[Msg, ByteString] = {
    case Msg(ch,data) =>

    println(s"Server: Down($ch, data.size=${data.size}) "+
      data.map(_.formatted("0x%02x")).mkString(", ") )

    val len = data.size + ( blockSize - data.size % blockSize ) % blockSize
    val len1 = ((data.size+blockSize-1)/blockSize)*blockSize
    assert(len==len1)
    val padded = data.padTo(len,0.toByte).toArray
    assert(padded.size % blockSize ==0)

    ByteString( MsgInfo(ch,data.size).toBytes() ) ++ ByteString(padded)
  }

  def run(listen_host: String, listen_port: Int, remote_host: String, remote_port:Int): Unit =
  {
    val config = ConfigFactory.parseString("akka.loglevel = DEBUG")
    implicit val system = ActorSystem(app, config)
    implicit val ec = system.dispatcher
    implicit val materializer = ActorMaterializer()

    println("Akka: "+system.name)

    val bufSize = 256
    val (toConnectionInformer, connectionInformer0) = MergeHub.source[(Int,Int)](bufSize).preMaterialize() // since 2.5.27

    val connectionInformer = connectionInformer0.groupedWithin(Int.MaxValue,1.seconds)
      // .map(  combineAll(_) ) // cats
      .map( _.reduce((a,b)=>(a._1+b._1,a._2+b._2)) )
      .map {
        val fmt = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
        var acc = (0,0)
        p =>
          //import cats.implicits._
          //acc = acc combine p // cats
          acc = (acc._1+p._1,acc._2+p._2)
          (fmt.format( Calendar.getInstance().getTime ), acc, p)
      }
      .toMat( BroadcastHub.sink(bufSize) )(Keep.right).run()

    val counterBidiFlow = BidiFlow.fromFlows(
      Flow.fromFunction(identity[ByteString]).alsoTo(Flow[ByteString].map(bs=>(bs.size,0)).to(toConnectionInformer)),
      Flow.fromFunction(identity[ByteString]).alsoTo(Flow[ByteString].map(bs=>(0,bs.size)).to(toConnectionInformer))
    )

    val encrypted =
      CryptoBidiFlow.bidiFlowReversed.joinMat( Tcp().outgoingConnection(remote_host, remote_port) )(Keep.right)

    val encountered =
      counterBidiFlow.joinMat(encrypted)(Keep.right)

    val commonFlow = Flow[Msg]
      .map(fromMsg)
      //.via(fromMsg)
      .wireTap( bs=>assert(bs.size % blockSize ==0) )
      .viaMat(encountered)(Keep.right)
      .viaMat(toLongMsg)(Keep.left)

    //val (bfSink, bfSource) = commonFlow.runWith( MergeHub.source[Msg](bufSize), BroadcastHub.sink[Msg](bufSize) )
    val ((bfSink, incoming), bfSource) = MergeHub.source[Msg](bufSize).viaMat(commonFlow)(Keep.both).toMat(BroadcastHub.sink[Msg](bufSize))(Keep.both).run()
    incoming.onComplete {
      case Success(connection) => println("SUCCESS Established tunnel connection to "+connection.remoteAddress+" from "+connection.localAddress)
      case Failure(error) => println("FAIL connect to remote server: "+error.getMessage)
    }

    val listen: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind(listen_host, listen_port)

    listen.runForeach { incomingConnection: IncomingConnection =>
      val ch = Channel.create( incomingConnection.localAddress.getPort )
      val connInfo = s"New incomingConnection from: ${incomingConnection.remoteAddress} assigned to channel $ch"
      println(connInfo)

      // note https://stackoverflow.com/questions/56903332/dynamically-merge-akka-streams
      val busFlow = Flow.fromSinkAndSourceCoupled(bfSink, bfSource)

      val workFlow = Flow[ByteString].map( bs=>Msg(ch,bs) ).via( busFlow.filter(_.ch==ch).map(_.data) )

      incomingConnection.handleWith( workFlow )

    }.failed.map(_.getMessage).foreach( println )

    //Source.fromPublisher( pubConnectionInformer ).runWith( Sink.ignore )
    // avoiding to shut-down the publisher when no subscribers

    println(s"$app initialization completed.")

    // HTTP service
    val route =
      path("status") {
        get {
          withRequestTimeoutResponse(request => HttpResponse(entity = "status timeout")) {
            encodeResponseWith(NoCoding)
            {
              complete {
                val chunked =
                  Source.fromIterator(()=>(Seq.fill(1000)(ChunkStreamPart(" ")):+ChunkStreamPart("CONNECTION INFORMATION:\n")).toIterator) ++
                  connectionInformer.map( _.toString() ).map { s => println("chunk "+s); ChunkStreamPart(s+"\n") }

                HttpResponse( entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, chunked) )
              }
            }
          }
        }
      }

    val bindingFuture = Http().bindAndHandle(route, http_host, http_port)
    bindingFuture.onComplete( _ match {
      case Success(_) => println(s"HTTP service is accepting connections at $http_host:$http_port")
      case Failure(e) => println( "ERROR: Cannot start HTTP service: "+e.getMessage )
    })

  } // end of run(...)
}

