import akka.NotUsed
import akka.util.ByteString
import akka.stream.scaladsl.{BidiFlow, Flow, Keep}
import com.mfglabs.stream.FlowExt
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.params.KeyParameter

import scala.concurrent.duration.DurationInt

object CryptoBidiFlow {

  // Cipher
  val key = "key12345".toArray.map(_.toByte) // TODO
  val kp = new KeyParameter(key)

  val cipherEnc = new BufferedBlockCipher(new TwofishEngine)
  cipherEnc.init(true, kp)

  val cipherDec = new BufferedBlockCipher(new TwofishEngine)
  cipherDec.init(false, kp)

  val blockSize = cipherEnc.getBlockSize

  // .via(FlowExt.debounce(100 microsecond,_.toString)

  def flow(cipher: BufferedBlockCipher) =
    Flow[ByteString]
      //.groupedWithin(100,100 millisecond)
        //.groupedWeightedWithin(1024,100 millisecond)(_.size) // need Akka 2.5.x ?
        //.map(_.reduce(_++_))
      .via(FlowExt.rechunkByteStringBySize(blockSize))
      .map(_.toArray).map {
      src =>
        val dest = new Array[Byte](src.size) // TODO: performance
        cipher.processBytes(src, 0, src.size, dest, 0)
        ByteString( dest )
    }

  def bidiFlow: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    BidiFlow.fromFlowsMat(flow(cipherDec), flow(cipherEnc))(Keep.left) // .both ?

  def bidiFlowReversed = bidiFlow.reversed
}
