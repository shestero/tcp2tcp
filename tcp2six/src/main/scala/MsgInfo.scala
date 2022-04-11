import AkkaMessages.Channel
import scodec.Attempt.Successful
import scodec.{Attempt, Codec, DecodeResult}
import scodec.bits.BitVector
import scodec.codecs._

object MsgInfo {
  val channelCodec = (uint32 :: uint16).as[Channel]
  val msgInfoCodec: Codec[MsgInfo] = (channelCodec :: int32).as[MsgInfo]
  // ^ Scala bug: https://github.com/scala/bug/issues/5091
  // reported as fixed since Scala 2.13.0-M4

  def apply(b: Array[Byte]) = {
    val attempt: Attempt[DecodeResult[(MsgInfo)]] = msgInfoCodec.decode( BitVector(b) )
    attempt match {
      case Successful(DecodeResult(mi, pading)) =>
        assert( pading.toByteArray.forall(_==0) )
        mi
    }
  }

}
case class MsgInfo(channel: Channel, dataSize: Int)
{
  def toBytes() = {
    MsgInfo.msgInfoCodec.encode(this) match {
      case Successful(bitv) => bitv.toByteArray.padTo(16,0.toByte)
    }
  }
}
