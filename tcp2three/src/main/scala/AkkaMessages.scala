import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import akka.util.ByteString

import scodec.{Attempt, Codec, DecodeResult}
import scodec.Attempt.Successful
import scodec.bits.BitVector
import scodec.codecs._


object AkkaMessages {

  case class Channel(connId: Long, port: Int) // it's not used as Akka message

  case class TCPClose(ch: Channel)

  case class PipeFail() // TODO: general upstream fail
  case class PipeClose(conn: Int)

  case class Msg(ch: Channel, data: ByteString)
}
