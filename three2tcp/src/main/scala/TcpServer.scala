import akka.actor._
import akka.io._
import java.net.InetSocketAddress

import akka.util.ByteString

import scala.concurrent.duration._
import AkkaMessages._
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.params.KeyParameter

import scala.language.postfixOps  // 2.13


class TcpServerHandler(client: ActorRef) extends Actor with ActorLogging
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
          client ! Msg( msgInfo.channel, ByteString(ready) )
          len = len-ready.length
          if (len<=0)
            context unbecome
      }, discardOld = false)
  }
}

class TcpServer(listen: InetSocketAddress, remote: InetSocketAddress) extends Actor with ActorLogging {
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, listen)

  // Encription - decription
  val key = "key12345".toArray.map(_.toByte) // TODO
  val kp = new KeyParameter(key)

  val cipherEnc = new BufferedBlockCipher(new TwofishEngine)
  cipherEnc.init(true, kp)

  val cipherDec = new BufferedBlockCipher(new TwofishEngine)
  cipherDec.init(false, kp)

  val blockSize = cipherEnc.getBlockSize
  var remain = ByteString()

  def receive = {
    case Bound(localAddresses) => println(s"DOWN server bound to $localAddresses ...")

    case CommandFailed(_: Bind) =>
      println("CommandFailed")
      context.stop(self)

    case Connected(remoteAddresses, localAddresses) =>
      println(s"new connection accepted from $remoteAddresses")
      sender ! Register(self) // TODO: new handler with TCPClient for every new connection !!
      println("registred...")

      // see also: context.parent
      val connection = sender

      val handler =
        context.actorOf(Props(classOf[TcpServerHandler],
        context.actorOf(Props(classOf[TcpClient], remote) )))

      context become({
        case Msg(ch, data) =>
          println(s"Server: Down($ch, data.size=${data.size}) "+
            data.map(_.formatted("0x%02x")).mkString(", ") )

          val stream = 0 // TODO: you can do partitioning to spread data into maxStreams parallel streams
          // val stream = ch.connId % maxStreams

          // 1. Service information
          val rawMsgInfo = MsgInfo(ch,data.size).toBytes()
          val encMsgInfo = new Array[Byte](rawMsgInfo.size)
          val lenMsgInfo = cipherEnc.processBytes(rawMsgInfo, 0, rawMsgInfo.size, encMsgInfo, 0)
          assert(rawMsgInfo.size==lenMsgInfo)
          //connection ! Write( ByteString(encMsgInfo) )

          // 2. User data itself
          val len = data.size + ( blockSize - data.size % blockSize ) % blockSize
          val len1 = ((data.size+blockSize-1)/blockSize)*blockSize
          assert(len==len1)
          val rawData = data.padTo(len,0.toByte).toArray
          val encData = new Array[Byte](len)
          val lenData = cipherEnc.processBytes(rawData, 0, len, encData, 0)
          assert(len==lenData)
          //connection ! Write( ByteString(encData) )

          connection ! Write( ByteString(encMsgInfo)++ByteString(encData) )

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

          dec.sliding(blockSize,blockSize).foreach( handler ! _ ) // Warning: support only single connection here! risk of mixing

        case cc: ConnectionClosed =>
          println("UP Connection closed. Reason: "+cc.getErrorCause)
          context unbecome  // dangerous!

        case x =>
          println("### something happened: "+x.getClass.getCanonicalName)

      }, discardOld = false)

      // TODO disconnect
  }

}
