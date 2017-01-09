package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.Future
import scala.concurrent.duration.Duration


/** Shut down the pipeline after a period of inactivity */
class QuietTimeoutStage[I, O](timeout: Duration, exec: TickWheelExecutor = scheduler) extends TimeoutStageBase[I, O](timeout, exec) {

  ////////////////////////////////////////////////////////////////////////////////

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    startTimeout()
  }

  override def readRequest(size: Int): Future[I] = {
    val f = channelRead(size)
    f.onComplete { _ => resetTimeout() }(directec)
    f
  }

  override def writeRequest(data: Seq[O]): Future[Unit] = {
    val f = channelWrite(data)
    f.onComplete { _ => resetTimeout() }(directec)
    f
  }

  override def writeRequest(data: O): Future[Unit] = {
    val f = channelWrite(data)
    f.onComplete { _ => resetTimeout() }(directec)
    f
  }
}
