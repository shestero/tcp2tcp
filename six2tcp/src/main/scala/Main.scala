import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Calendar

import AkkaMessages.{Channel, Msg}
import CryptoBidiFlow.blockSize
import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.NoCoding
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.io.Tcp.SO.{KeepAlive, TcpNoDelay}
import akka.stream.QueueOfferResult.{Dropped, Enqueued, Failure, QueueClosed}
import akka.stream.{ActorAttributes, ActorMaterializer, Materializer, OverflowStrategy, QueueOfferResult, Supervision}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.scaladsl.{BidiFlow, BroadcastHub, Flow, Keep, MergeHub, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import com.karasiq.proxy.server.ProxyConnectionRequest

import scala.collection.mutable

//import scala.collection.mutable
import scala.concurrent.ExecutionContext
//import com.karasiq.proxy.server.{ProxyConnectionRequest, ProxyServer}
import com.typesafe.config.ConfigFactory

import com.karasiq.proxy.server.ProxyServer
//import com.mfglabs.stream.FlowExt

import scala.concurrent.Future
import scala.util.Try

import scala.concurrent.duration._


object Main extends App {

  val app = "six2tcp"

  val bufferSize = 1024

  // NB note https://github.com/scopt/scopt
  println(s"Hi\t$app [listen_host [listen_port [ [remote_port]]]]")
  val def_listen_host = "localhost"
  val def_listen_port = 8009
  val def_remote_host = "localhost"
  val def_remote_port = 1080
  val http_host = "localhost"
  val http_port = 8090
  println(s"Where defaults are: $def_listen_host, $def_listen_port")

  val argmap = ((Stream from 1) zip args).toMap
  Try {
    (
      argmap.getOrElse(1, def_listen_host),
      argmap.get(2).map(_.toInt).getOrElse(def_listen_port)
    )
  }.map{ params =>
    println(s"So using: $params")
    run _ tupled params
  }.failed.map { e =>
    println(e.getMessage)
  }

  case class QueueOfferErrors(errors: List[QueueOfferResult]) extends Throwable {
    def :::(other: QueueOfferErrors) = QueueOfferErrors(errors:::other.errors)
  }
  object QueueOfferErrors {
    def apply(error: QueueOfferResult): QueueOfferErrors = new QueueOfferErrors(List(error))
  }

  /*
  def flowPartitioner[I,O,P](partitioner: I=>P, flower: P=>Flow[I,O,NotUsed], bufSize: Int = 256) // flower is a creator of flows :-)
                            (implicit mat: Materializer, ec: ExecutionContext): Flow[I,O,NotUsed] =
  {
    val (toConsumer, source) = MergeHub.source[O](bufSize).preMaterialize() // since 2.5.27

    val sink = Sink.foreachAsync[I](1) // since 2.5.17
    {
      val m = scala.collection.mutable.Map.empty[P, SourceQueueWithComplete[I]]
      elem: I =>
        val partition = partitioner(elem)
        m.getOrElseUpdate(
          partition,
          Source.queue[I](bufSize,OverflowStrategy.backpressure).via( flower(partition) ).to(toConsumer).run()
        ).offer(elem).flatMap
        {
          case Enqueued => Future.successful(Unit)
          case error => Future.failed( QueueOfferError(error) )
        }
    }

    Flow.fromSinkAndSourceCoupled(sink,source)  // fromSinkAndSourceCoupled not yet in Akka 2.4 ?
  }
  */

  def flowPartitionerMat[I,O,P,M](partitioner: I=>P, flower: P=>Flow[I,O,M], bufSize: Int = 256) // "flower" is a creator of flows :-)
                                (implicit materializer: Materializer, ec: ExecutionContext): Flow[I,O,Source[M,NotUsed]] =
  {
    val (toConsumer, source) = MergeHub.source[O](bufSize).preMaterialize() // since 2.5.27

    val (queueMat,sourceMat) = Source.queue[M](bufSize, OverflowStrategy.backpressure).preMaterialize()

    val sink = Sink.foreachAsync[I](1) // since 2.5.17
    {
      val map = scala.collection.mutable.Map.empty[ P, (Future[QueueOfferResult],SourceQueueWithComplete[I]) ]
      elem: I =>
        val partition = partitioner(elem)
        val (futMat,queueVal) = map.getOrElseUpdate( partition,
          Source.queue[I](bufSize, OverflowStrategy.backpressure).viaMat( flower(partition) )(Keep.both).toMat(toConsumer)(Keep.left).mapMaterializedValue {
            case (queueValNew, mat) => queueMat.offer(mat) -> queueValNew
          }.run()
        )
        val futVal = queueVal.offer(elem)

        Future.reduceLeft(List(
          for (fe <- futMat if fe != Enqueued) yield QueueOfferErrors(fe),
          for (fe <- futVal if fe != Enqueued) yield QueueOfferErrors(fe)
        ))(_:::_).transform(_.transform[Unit](util.Failure(_),_=>util.Success(Unit))) // "flip" the future
    }

    Flow.fromSinkAndSourceCoupledMat(sink,source)(Keep.none).mapMaterializedValue(_=>sourceMat)
    // { case (_,_) => sourceMat.mapMaterializedValue(_=>NotUsed) }
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

  def fromMsg = Flow.fromFunction[Msg,ByteString]
  {
    case Msg(ch,data) =>
      val len = data.size + ( blockSize - data.size % blockSize ) % blockSize
      val len1 = ((data.size+blockSize-1)/blockSize)*blockSize
      assert(len==len1)
      val padded = data.padTo(len,0.toByte).toArray
      assert( padded.size % blockSize == 0 )

      ByteString(MsgInfo(ch,data.size).toBytes()) ++ ByteString(padded)
  }

  val connectTimeout = 5.second

  def run(listen_host: String, listen_port: Int): Unit =
  {
    val config = ConfigFactory.parseString("akka.loglevel = DEBUG")
    implicit val system = ActorSystem(app, config)
    implicit val ec = system.dispatcher
    implicit val materializer = ActorMaterializer()

    println("Akka: "+system.name)

    val bufSize = 256
    val (toConnectionInformer, connectionInformer0) = MergeHub.source[((String,String),(Int,Int))](bufSize).preMaterialize() // since 2.5.27

    val connectionInformer = connectionInformer0.groupedWithin(Int.MaxValue,1.seconds)
      .map( _.foldLeft(Map.empty[(String,String),(Int,Int)] )({
        case (m, v @ (fh,p)) =>
          m + (m get fh match {
            case Some(o) => fh->(p._1+o._1,p._2+o._2)
            case None => v
          })
        })
      ).mapConcat // [(String,String,String,(Int,Int))]
      {
        val fmt = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
        val ts: String = fmt.format( Calendar.getInstance().getTime )
        _.toIterable.asInstanceOf[scala.collection.immutable.Iterable[_]].map {
          case ((f:String,h:String),p:(Int,Int)) => (ts, f, h, p)
        }
      }
      .toMat( BroadcastHub.sink(bufSize) )(Keep.right).run()

    def counterBidiFlow(f:String, h:String) = BidiFlow.fromFlows(
      Flow.fromFunction(identity[ByteString]).alsoTo(Flow[ByteString].map(bs=>(f,h)->(bs.size,0)).to(toConnectionInformer)),
      Flow.fromFunction(identity[ByteString]).alsoTo(Flow[ByteString].map(bs=>(f,h)->(0,bs.size)).to(toConnectionInformer))
    )

    def bind( listen_host: String, listen_port: Int ) = {
      val listen = Tcp().bind(listen_host, listen_port) // , options = List(KeepAlive(true), TcpNoDelay(true)) )
      //val (bi,pr) = listen.preMaterialize()

      val (binding, process) = listen.toMat(Sink.foreach { incomingConnection: IncomingConnection =>

        println(s"New incomingConnection from: ${incomingConnection.remoteAddress}")

        val decryptedFlow = Flow[ByteString]
          .via(toLongMsg)
          .viaMat(flowPartitionerMat(_.ch, (ch: Channel) => {
            val internalProxy = ProxyServer().buffer(bufferSize, OverflowStrategy.backpressure)
            Flow[Msg].map(_.data).viaMat(internalProxy)(Keep.right).map(bs => Msg(ch, bs))
          }
          ))(Keep.right).via(fromMsg)

        incomingConnection.handleWith(CryptoBidiFlow.bidiFlow.joinMat(decryptedFlow)(Keep.right)).runForeach {
          _.foreach {
            case (request, flow) ⇒
              println(s"Processing proxy request $request")

              val outcomingConnection = Tcp().outgoingConnection(request.address)
              ProxyServer.withSuccess(flow, request)
                .join(counterBidiFlow(
                  incomingConnection.remoteAddress.getHostName,
                  request.address.getHostName + ":" + request.address.getPort)
                ).joinMat(
                //counterBidiFlow(request.address.getHostName+":"+request.address.getPort).joinMat( outcomingConnection )(Keep.right)
                outcomingConnection
              )(Keep.right).run().recover {
                case (th: Throwable) =>
                  println("outcomingConnection error: " + th.getMessage)
                  Source.failed(th).via(ProxyServer.withFailure(flow, request)).runWith(Sink.cancelled)
              }
          }
        }
      })(Keep.both).run()
      process.failed.map(_.getMessage).foreach(err => println("FAILED: " + err))
      binding
    }

    //val binding = bind( listen_host, listen_port )
    var binding: Option[Future[Tcp.ServerBinding]] = None

    println(s"$app initialization completed.")

    // HTTP service
    val route =
      get {
        path( "start") {
          complete {

            binding.synchronized(binding).flatMap(_.value) match {
              case None => // | Some(util.Success(b)) if b.whenUnbound.isCompleted =>
                val chunked = Source.fromIterator(() => (Stream.fill(1000)(ChunkStreamPart(" ")) :+
                  ChunkStreamPart(s"<h3>Start</h3>\nGoing to bind... ")).toIterator) ++
                  Source.fromFuture({
                    val binding0 = bind(listen_host, listen_port)
                    binding.synchronized { binding = Some(binding0) }
                    binding0
                    }
                    .map(_ => " Successfully bound!<br/>")
                    .recover { case e => s" FAIL to bind: ${e.getMessage}<br/>" }
                    .map(ChunkStreamPart(_))
                  ) ++
                  Source.single(ChunkStreamPart("\nYou may want to <a href='/stop'>stop</a>.\n")) ++
                  Source.single(ChunkStreamPart("\nsee <a href='/status'>status</a>.\n"))
                HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/html(UTF-8)`, chunked))

              case Some(util.Success(b)) =>
                val bound = b.localAddress.getHostName + ":" + b.localAddress.getPort
                HttpEntity(ContentTypes.`text/html(UTF-8)`,
                  s"<h3>Start</h3>\nAlready bound to $bound!<br/>" +
                    "\nYou may want to <a href='/stop'>stop</a> or\n" +
                    "\nsee <a href='/status'>status</a>.\n"
                )

              case Some(util.Failure(e)) =>
                HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
                  s"<h3>Stop</h3>\nCannot bound!\n${e.getMessage}<hr/>\nSee <a href='/status'>status</a>.\n"))

            }
          }
        } ~
        path("stop") {
          complete {
            binding.synchronized(binding).flatMap(_.value) match {
              case None =>
                HttpResponse( entity = HttpEntity( ContentTypes.`text/html(UTF-8)`,
                  "<h3>Stop</h3>\nHavn't bound yet!\n<hr/>"+
                  "\nYou may want to <a href='/start'>start</a> or"+
                  "\nSee <a href='/status'>status</a>.\n")
                )

              case Some(util.Success(b)) =>
                val bound = b.localAddress.getHostName+":"+b.localAddress.getPort
                if (b.whenUnbound.isCompleted) {
                  HttpResponse( entity = HttpEntity( ContentTypes.`text/html(UTF-8)`,
                    s"<h3>Stop</h3>\nSwitched OFF! was bound to $bound\n<hr/>"+
                    "\nYou may want to <a href='/start'>start</a> or"+
                    "\nSee <a href='/status'>status</a>.\n") )
                }
                else {
                  val chunked = Source.fromIterator(() => (Stream.fill(1000)(ChunkStreamPart(" ")) :+
                    ChunkStreamPart(s"<h3>Stop</h3>\nBound to $bound. Going to unbind... ")).toIterator) ++
                    Source.fromFuture(b.unbind()
                      .map { _ =>
                        binding.synchronized { binding = None } // Note
                        " Successfully unbound (stopped)!<br/>\nYou may want to <a href='/start'>start</a> or\n"
                      }
                      .recover { case e => " FAIL to unbind: " + e.getMessage }
                      .map(ChunkStreamPart(_))
                    ) ++
                    Source.single(ChunkStreamPart("\nsee <a href='/status'>status</a>.\n"))
                  HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/html(UTF-8)`, chunked))
                }

              case Some(util.Failure(e)) =>
                HttpResponse( entity = HttpEntity( ContentTypes.`text/html(UTF-8)`,
                  s"<h3>Stop</h3>\nCannot unbound!\n${e.getMessage}<hr/>\nSee <a href='/status'>status</a>.\n") )
            }
          }
        } ~
        path("status") {
          withRequestTimeoutResponse(request => HttpResponse(entity = "status timeout")) {
            encodeResponseWith(NoCoding)
            {
              complete {
                val mm = mutable.Map.empty[String,(Int,Int)] // "global" counter

                val chunked =
                  Source.fromIterator( ()=>(Stream.fill(1000)(ChunkStreamPart(" ")):+
                    ChunkStreamPart("CONNECTION INFORMATION:\n")).toIterator ) ++
                  Source.single( binding.synchronized(binding).flatMap(_.value) match {
                    case None => "OFF"
                    case Some(util.Success(b)) =>
                      "ACTIVE. Bound to " + b.localAddress.getHostName + ":" + b.localAddress.getPort
                    case Some(util.Failure(e)) => "ERROR: "+e.getMessage
                  }).map("STATUS:"+_+"\n").map(ChunkStreamPart(_))  ++
                  connectionInformer.map {
                    case (t,f,h,p) =>
                      val s = mm get h match {
                        case Some(o) => (p._1+o._1,p._2+o._2)
                        case None => p
                      }
                      mm.update(h,s)
                      (t,f,h,p,s)
                  }.map( _.toString() ).map { s => println("chunk "+s); ChunkStreamPart(s+"\n") }

                HttpResponse( entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, chunked) )
              }
            }
          }
        }
      }

    val bindingFuture = Http().bindAndHandle(route, http_host, http_port)
    bindingFuture.onComplete( _ match {
      case util.Success(_) => println(s"HTTP service is accepting connections at $http_host:$http_port")
      case util.Failure(e) => println( "ERROR: Cannot start HTTP service: "+e.getMessage )
    })

  } // end of run(...)
}


/*
import com.karasiq.proxychain.app.Connector
val connector = Connector(appConfig)
def runViaChain(tcpConn: IncomingConnection, request: ProxyConnectionRequest, connection: Flow[ByteString, ByteString, _]): Unit = {
  // log.info("{} connection request: {}", request.scheme.toUpperCase, request.address)
  connector.connect(request, tcpConn.remoteAddress)
    .onComplete {
      case Success((_, proxy)) ⇒
        ProxyServer.withSuccess(connection, request)
          .join(proxy)
          .run()

      case Failure(exc) ⇒
        Source.failed(exc)
          .via(ProxyServer.withFailure(connection, request))
          .runWith(Sink.cancelled)
    }
}

def socks5Flow: Flow[Msg,Msg,NotUsed] = flowPartitioner[Msg,Msg,Channel](
  _.ch,
  ch=> Flow[Msg].map(_.data).via(ProxyServer().buffer(8, OverflowStrategy.backpressure)).map(bs=>Msg(ch,bs))
)

println("Creating server handler flow with ciphers...")
def serverFlow = toMsg via socks5Flow via fromMsg

//val handler = CryptoBidiFlow.bidiFlow join serverFlow

// was: CryptoBidiFlow.bidiFlow.join(actorFlow)
*/


// internal ProxyServer example:
/*
connection.handleWith(ProxyServer()).foreach {
  case (request, conn) ⇒
    println(s"Request $request via $conn")
    //runViaChain(connections, request, conn)

    val out = Tcp().outgoingConnection(request.address)
    //conn.join(out).run()
    //out.join(conn).run()
    ProxyServer.withSuccess(conn, request).join(out).run()
}
*/
