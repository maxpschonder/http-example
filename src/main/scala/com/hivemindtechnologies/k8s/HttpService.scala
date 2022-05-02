package com.hivemindtechnologies.k8s

import zio._
import zio.logging._
import zhttp.http._
import zhttp.service.{EventLoopGroup, Server}
import zhttp.service.server.ServerChannelFactory
import zio.clock.Clock
import zio.console.Console

object HttpService extends App {
  val app: Http[Any, Nothing, Request, Response] = Http.collect[Request] {
    case Method.GET -> !!            => Response.text(s"Hello World!")
    case Method.GET -> !! / "livez"  => Response.json("""{ "status": "live"}""")
    case Method.GET -> !! / "readyz" => Response.json("""{ "status": "ready"}""")
  }

  val loggingLayer: ZLayer[Console with Clock, Nothing, Logging] =
    Logging.console() >>>
      Logging.withRootLoggerName("example-http-service")

  val server: Server[Any, Nothing] = Server.port(8090) ++ Server.app(app)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    server.make
      .use(start => log.info(s"Server started on port ${start.port}") *> ZIO.never)
      .provideCustomLayer(ServerChannelFactory.auto ++ EventLoopGroup.auto(4) ++ loggingLayer)
      .exitCode
  }
}
