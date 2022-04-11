import akka.NotUsed
import akka.util.ByteString
import akka.stream.scaladsl.{BidiFlow, Flow, Keep}

import scala.io.Source
import scala.util.Try
//import com.mfglabs.stream.FlowExt
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.params.KeyParameter

import scala.concurrent.duration.DurationInt

object CryptoBidiFlow {

  val keyfile = "key.txt"
  val key = Source.fromFile(keyfile).getLines.take(1).toList.head // headOption
  // val key = "key12345"
  println("KEY="+key)
  val kp = new KeyParameter( key.toArray.map(_.toByte) )

  val cipherEnc = new BufferedBlockCipher(new TwofishEngine)
  cipherEnc.init(true, kp)

  val cipherDec = new BufferedBlockCipher(new TwofishEngine)
  cipherDec.init(false, kp)

  val blockSize = cipherEnc.getBlockSize
  assert(blockSize==cipherDec.getBlockSize)

  // .via(FlowExt.debounce(100 microsecond,_.toString)

  def flow(cipher: BufferedBlockCipher) =
    Flow[ByteString]
      //.groupedWithin(100,100 millisecond)
        //.groupedWeightedWithin(1024,100 millisecond)(_.size) // need Akka 2.5.x ?
        //.map(_.reduce(_++_))

      //.via(FlowExt.rechunkByteStringBySize(blockSize))

      //.sliding(blockSize,blockSize) // block!!

      // working:
      //.mapConcat( _.grouped(blockSize).toIterable.asInstanceOf[scala.collection.immutable.Iterable[ByteString]] )

    //.mapConcat(_.toSeq).grouped(blockSize).map(_.map(b=>ByteString(b)).foldLeft(ByteString())(_++_))

    .map(_.toArray).mapConcat {
    src =>
      if ( src.size % blockSize != 0 ) println("! src.size="+src.size)
      if ( src.size != blockSize ) println("[note] src.size="+src.size)
      assert( src.size % blockSize ==0 )
      val dest = new Array[Byte](src.size+blockSize ) // TODO: performance
      val destSize = cipher.processBytes(src, 0, src.size, dest, 0)
      if (src.size!=destSize) println(s"! src.size=${src.size} destSize=$destSize")
      if (destSize<=0)
        List.empty
      else
        ByteString( dest.take(destSize) ).grouped(blockSize).toList
    }

  def bidiFlow: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
      BidiFlow.fromFlowsMat(flow(cipherDec), flow(cipherEnc))(Keep.left)

  def bidiFlowReversed = bidiFlow.reversed
}
