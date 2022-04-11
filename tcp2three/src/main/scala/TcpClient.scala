import akka.io._
import akka.actor._
import akka.pattern.ask

import scala.concurrent.duration._
import java.net.InetSocketAddress

import akka.util.ByteString
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.params.KeyParameter

import scala.annotation.tailrec
import scala.util.{Failure, Success}

// import akka.util.ByteString

import scala.concurrent.duration._
import AkkaMessages._

import scala.language.postfixOps  // 2.13

class TcpClientHandler(server: ActorRef) extends Actor with ActorLogging
{
  val blockSize = 16 // TODO; temp

  override def receive: Receive = {
    case data: Array[Byte] =>
      println(s"receive msgInfo data.size=${data.size}")
      assert(data.size==blockSize)
      val msgInfo = MsgInfo(data)
      println(s"got msgInfo: $msgInfo")
      var len = msgInfo.dataSize

      context become({
        case data: Array[Byte] =>
          assert(data.size==blockSize)

          val ready = data.take(len)
          println( "ready="+ready.map(_.formatted("0x%02x")).mkString(", ") )
          server ! Msg( msgInfo.channel, ByteString(ready) )
          len = len-ready.length
          if (len<=0)
            context unbecome
      }, discardOld = false)
  }
}

class TcpClient(listen: InetSocketAddress, remote: InetSocketAddress) extends Actor with ActorLogging
{
  import Tcp._

  //import context.system
  implicit val system = context.system
  import system.dispatcher

  val tcpio = IO(Tcp)
  tcpio ! Connect(remote)

  // Encription - decription
  val key = "key12345".toArray.map(_.toByte) // TODO
  val kp = new KeyParameter(key)

  val cipherEnc = new BufferedBlockCipher(new TwofishEngine)
  cipherEnc.init(true, kp)

  val cipherDec = new BufferedBlockCipher(new TwofishEngine)
  cipherDec.init(false, kp)

  val blockSize = cipherEnc.getBlockSize

  var expect = 0
  var channel: Channel = null
  var remain = ByteString()

  println(s"Client trying connect ...")

  def receive: Receive = {
    case Connected(remoteAddresses, localAddresses) =>
      println(s"Client: set connection to $remoteAddresses")

      val connection = sender
      connection ! Register(self)

      val handler =
        context.actorOf(Props(classOf[TcpClientHandler],
        context.actorOf(Props(classOf[TcpServer],listen), "tcp-server") ) )

      context become({
        case Msg(ch, data) => // note that this message should by send after the client is connected !
          println(s"Client sending Tcp2Pipe($ch, payload.size=${data.size}) "+
            data.map(_.formatted("0x%02x")).mkString(", ") )

          val stream = 0 // TODO: you can do partitioning to spread data into maxStreams parallel streams
          // val stream = ch.connId % maxStreams

          // 1. Service information
          val rawMsgInfo = MsgInfo(ch,data.size).toBytes()
          val encMsgInfo = new Array[Byte](rawMsgInfo.size)
          val lenMsgInfo = cipherEnc.processBytes(rawMsgInfo, 0, rawMsgInfo.size, encMsgInfo, 0)
          assert(rawMsgInfo.size==lenMsgInfo)
          //connection ! Write( ByteString(encMsgInfo) )
          //println(s"sent MsgInfo.size=${rawMsgInfo.size}")

          // 2. User data itself
          val len = data.size + ( blockSize - data.size % blockSize ) % blockSize
          val rawData = data.padTo(len,0.toByte).toArray
          val encData = new Array[Byte](len)
          val lenData = cipherEnc.processBytes(rawData, 0, len, encData, 0)
          assert(len==lenData)
          //connection ! Write( ByteString(encData) )
          //println(s"sent data; size=${data.size} padded len=$len encoded len=${encData.size}")

          connection ! Write( ByteString(encMsgInfo)++ByteString(encData) )
          println(s"sent data; size=${data.size} padded len=$len encoded len=${encData.size}")

        case Received(data) =>
          println(s"received ${data.size} bytes")

          val ready = remain++data
          val size = (ready.size / blockSize)*blockSize
          val (enc,remain0) = ready.splitAt(size)
          remain = remain0
          val dec = new Array[Byte](enc.size)
          val len1 = cipherDec.processBytes(enc.toArray, 0, enc.size, dec, 0)
          assert(enc.size==len1)
          println(s"BLOCK READY; size=$size, blockRemain.size=${remain.size}")

          dec.sliding(blockSize,blockSize).foreach( handler ! _ ) // Warning! we are to have only one client connection (and handler)

      }, discardOld = false)
      // TODO disconnect

    case CommandFailed(cmd: Connect) =>
      println(s"*** CommandFailed cmd=$cmd")
      system.scheduler.scheduleOnce(5.seconds, tcpio, cmd) // reconnect?

    case x =>
      println("### something happened: "+x.getClass.getCanonicalName)

  }
}
