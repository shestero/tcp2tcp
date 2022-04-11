import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, PartitionHub, RunnableGraph, Sink, Source}
import scala.collection.mutable


object FlowPartitioner {
  /*
  def apply[S, D](
                   partitioner: (S, Int) => Int,
                   dynamicFlow: (Int) => Flow[S, D, NotUsed],
                   initialPartCount: Int,
                   bufSize: Int = 8
                 )
                 (implicit m: Materializer) : Flow[S, D, NotUsed] = {
    val c = new FlowPartitioner[S, D, Int](partitioner, dynamicFlow, bufSize)(m)
    for (i<- 0 to initialPartCount) c.addPartition(i)
    c.flow
  }
  */

  def apply[S, D, K](
                      partitioner: (S, Int) => K,
                      dynamicFlow: (Int) => Flow[S, D, NotUsed],
                      bufSize: Int = 8
                    )
                    (implicit m: Materializer) : Flow[S, (K,D), NotUsed] = {



    val fp = new FlowPartitioner[S, D, K](partitioner, dynamicFlow, bufSize)(m)
    fp.addPartition(0)
    fp.flow
  }
}

private class FlowPartitioner[S, D, K](partitioner: (S, Int) => K,
                                       dynamicFlow: (Int) => Flow[S, D, NotUsed],
                                       bufSize: Int)
                                   (implicit m: Materializer)
{
  def bindedSinkAndSource[T](implicit m: Materializer): RunnableGraph[(Sink[T, NotUsed], Source[T, NotUsed])] = {
    MergeHub.source[T](perProducerBufferSize = bufSize).toMat(BroadcastHub.sink[T](bufferSize = bufSize))(Keep.both)
  }
  val (inp, src) = bindedSinkAndSource[S].run()
  val (dst, out) = bindedSinkAndSource[(K,D)].run()
  val flow = Flow.fromSinkAndSource(inp, out)

  val k2i  = mutable.Map.empty[K,Int]
  val i2k = mutable.Map.empty[Int,K]

  def subPartitioner(s: S, currentCount: Int): Int = {
    val k = partitioner(s, currentCount)
    k2i.getOrElse(k,{
      val sz = k2i.size
      k2i.put(k,sz)
      i2k.put(sz,k)
      sz
    })
  }

  val fromProducerOfPartitioner: Source[S, NotUsed] = src.toMat(
    PartitionHub.sink[S](
      (countOfPartitions, elem) => {
        val partition = subPartitioner(elem, countOfPartitions-1)
        for (i <- countOfPartitions-1 to partition) addPartition(i) // add more partitions - flows
        partition
      },
      startAfterNrOfConsumers = 1,
      bufferSize = bufSize)
  )(Keep.right).run()

  val toConsumerOfMerger = MergeHub.source[(K,D)](perProducerBufferSize = bufSize)
    .to(dst).run()

  def addPartition(num: Int): Unit = {
    println(s"Adding consumer #$num")
    fromProducerOfPartitioner.via(dynamicFlow(num).map((i2k(num),_))).to(toConsumerOfMerger).run()
  }
}
