package org.http4s.blaze.pipeline.stages

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.pipeline.Command.{ Disconnect, Disconnected }
import org.http4s.blaze.util.{ Cancellable, TickWheelExecutor }

import java.util.concurrent.atomic.AtomicReference

abstract class TimeoutStageBase[I, O](timeout: Duration, exec: TickWheelExecutor) extends MidStage[I, O, I, O] { stage =>

  import TimeoutStageBase.closedTag

  // Constructor
  require(timeout.isFinite() && timeout.toMillis != 0, s"Invalid timeout: $timeout")

  override def name: String = s"${this.getClass.getName} Stage: $timeout"

  /////////// Private impl bits //////////////////////////////////////////

  private val lastTimeout = new AtomicReference[Cancellable](Cancellable.noopCancel)

  private val killswitch = new Runnable {
    override def run(): Unit = {
      logger.debug(s"Timeout of $timeout triggered. Killing pipeline.")
      sendOutboundCommand(Disconnect)
      sendInboundCommand(Disconnected)
    }
  }

  @tailrec
  final def setAndCancel(next: Cancellable): Unit = {
    val prev = lastTimeout.getAndSet(next)
    if (prev == closedTag && next != closedTag) {
      // woops... we submitted a new cancellation when we were closed!
      next.cancel()
      setAndCancel(closedTag)
    }
    else prev.cancel()
  }

  /////////// Pass through implementations ////////////////////////////////

  override def readRequest(size: Int): Future[I] = channelRead(size)

  override def writeRequest(data: O): Future[Unit] = channelWrite(data)

  override def writeRequest(data: Seq[O]): Future[Unit] = channelWrite(data)

  /////////// Protected impl bits //////////////////////////////////////////

  override protected def stageShutdown(): Unit = {
    setAndCancel(closedTag)
    super.stageShutdown()
  }

  final protected def resetTimeout(): Unit = setAndCancel(exec.schedule(killswitch, timeout))

  final protected def cancelTimeout(): Unit = setAndCancel(Cancellable.noopCancel)

  final protected def startTimeout(): Unit = resetTimeout()
}

private object TimeoutStageBase {
  // Represents the situation where the pipeline has been closed.
  val closedTag = new Cancellable {
    override def cancel(): Unit = ()
  }
}
