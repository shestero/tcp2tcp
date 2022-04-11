import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import akka.util.ByteString

object AkkaMessages {

  case class Channel(connId: Long, port: Int) // it's not used as Akka message
  {
    def isEmpty = connId==0L && port==0
  }
  object Channel {
    def empty = Channel(0L,0)
  }

  case class TCPClose(ch: Channel)

  case class PipeFail() // TODO: general upstream fail
  case class PipeClose(conn: Int)

  case class Msg(ch: Channel, data: ByteString)
  {
    def isEmpty = ch.isEmpty && data.isEmpty
  }
  object Msg {
    def empty = Msg( Channel.empty, ByteString() )
  }
}
