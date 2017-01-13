package org.http4s.blaze.http


import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution._
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import org.http4s.websocket.WebsocketHandshake

import scala.util.{Failure, Success, Try}
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.ArrayBuffer
import org.http4s.blaze.http.parser.Http1ServerParser
import org.http4s.blaze.http.parser.BaseExceptions.BadRequest
import org.http4s.blaze.http.websocket.WebSocketDecoder
import java.util.Date
import java.nio.ByteBuffer

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.annotation.tailrec

class HttpServiceStage(ec: ExecutionContext)(handleRequest: HttpService)
    extends TailStage[HttpRequest, ResponseBuilder] { httpServerStage =>
  import HttpServiceStage._
  import HttpServerStage._

  private implicit def implicitEC = trampoline

  val name = "HTTP/1.1 service stage"

  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting HttpStage")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    channelRead().onComplete {
      case Success(req)     => readLoop(req)
      case Failure(Cmd.EOF) => // NOOP
      case Failure(t)       => shutdownWithCommand(Cmd.Error(t))
    }
  }

  private def readLoop(req: HttpRequest): Unit = {
    try {
      runRequest(req)
    }
    catch { case t: Throwable => shutdownWithCommand(Cmd.Error(t)) }
  }

  private def runRequest(request: HttpRequest): Unit = {
    try handleRequest(request).onComplete {
      case Success(resp: HttpResponse) => channelWrite(resp)
      case Success(resp: WSResponseBuilder) => handleWebSocket(request.headers, resp)
      case Failure(e) => send500(e)
    }(ec)
    catch { case e: Throwable => send500(e) }
  }

  private def send500(error: Throwable): Unit = {
    logger.error(error)("Failed to select a response. Sending 500 response.")
    val body = ByteBuffer.wrap("Internal Service Error".getBytes(StandardCharsets.ISO_8859_1))

    channelWrite(InternalServerErrorResponse).onComplete { _ =>
      shutdownWithCommand(Cmd.Error(error))
    }
  }

  private def completionHandler(result: Try[RouteResult]): Unit = result match {
    case Success(completed) => sendOutboundCommand(completed)
    case Failure(t: BadRequest)   => badRequest(t)
    case Failure(t)               => shutdownWithCommand(Cmd.Error(t))
  }

  /** Deal with route response of WebSocket form */
  private def handleWebSocket(reqHeaders: Headers, resp: WSResponseBuilder): Future[RouteResult] = {
    val sb = new StringBuilder(512)
    WebsocketHandshake.serverHandshake(reqHeaders) match {
      case Left((i, msg)) =>
        logger.info(s"Invalid handshake: $i: $msg")
        val errorResponse = RouteAction.byteBuffer(i, msg, Nil, EmptyBody)
        channelWrite(errorResponse).map(_ => Close)

      case Right(hdrs) =>
        channelWrite(resp).map(_ => Upgrade)
    }
  }

  private def badRequest(msg: BadRequest): Unit = {
    val sb = new StringBuilder(512)
    channelWrite(BadRequestResponse).onComplete(_ => shutdownWithCommand(Cmd.Disconnect))
  }

  private def shutdownWithCommand(cmd: Cmd.OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  private def isKeepAlive(version: HttpVersion, headers: Headers): Boolean = {
    val h = headers.find {
      case (k, _) if k.equalsIgnoreCase("connection") => true
      case _                                          => false
    }

    h match {
      case Some((k, v)) =>
        if (v.equalsIgnoreCase("Keep-Alive")) true
        else if (v.equalsIgnoreCase("close")) false
        else if (v.equalsIgnoreCase("Upgrade")) true
        else {
          logger.info(s"Bad Connection header value: '$v'. Closing after request.")
          false
        }

      case None if version.minor == 0 => false
      case None => true
    }
  }
}

object HttpServiceStage {
  private val EmptyBody = ByteBuffer.allocate(0)
  private val BadRequestResponse = RouteAction.byteBuffer(400, "Bad Request", Nil, EmptyBody)
  private val InternalServerErrorResponse = RouteAction.byteBuffer(500, "Internal Server Error", Nil, EmptyBody)
}
