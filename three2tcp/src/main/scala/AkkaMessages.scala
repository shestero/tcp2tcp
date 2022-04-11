import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import akka.util.ByteString

object AkkaMessages {

  case class Channel(connId: Long, port: Int) // it's not used as Akka message

  case class TCPClose(ch: Channel)

  case class PipeFail() // TODO: general upstream fail
  case class PipeClose(conn: Int)

  case class Msg(ch: Channel, data: ByteString)
}
