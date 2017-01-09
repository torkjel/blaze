package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.{Command, MidStage}
import org.http4s.blaze.util.Execution.directec

import scala.concurrent.Future
import scala.collection.immutable.VectorBuilder
import org.http4s.blaze.pipeline.Command.OutboundCommand

abstract class BufferingStage[I, O](bufferSize: Int, val name: String = "BufferingStage")
    extends MidStage[I, O, I, O] {

  private val buffer = new VectorBuilder[O]
  private var size = 0

  protected def measure(buffer: O): Int

  // Just forward read requests
  def readRequest(size: Int): Future[I] = channelRead(size)

  def writeRequest(data: O): Future[Unit] = {

    val dsize = measure(data)
    buffer += data

    if (dsize + size >= bufferSize) flush()
    else {
      size = size + dsize
      Future.successful(())
    }
  }

  private def flush(): Future[Unit] = {
    val f = writeRequest(buffer.result)
    buffer.clear()
    size = 0
    f
  }

  override protected def stageShutdown(): Unit = {
    buffer.clear()
    size = 0
    super.stageShutdown()
  }

  override def outboundCommand(cmd: OutboundCommand): Unit = {
    cmd match {
      case Command.Flush => flush().onComplete(_ => super.outboundCommand(cmd))(directec)
      case cmd => super.outboundCommand(cmd)
    }
  }
}
