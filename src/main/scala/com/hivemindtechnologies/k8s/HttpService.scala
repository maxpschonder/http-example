package com.hivemindtechnologies.k8s

import zio._
import zio.logging._
import zhttp.http._
import zhttp.service.{EventLoopGroup, Server}
import zhttp.service.server.ServerChannelFactory
import zio.clock.Clock
import zio.console.Console

object HttpService extends App {
  // an http object takes incoming objects and produces outgoing objects with an effect
  // sealed trait Http[-R, +E, -A, +B] extends (A => ZIO[R, Option[E], B])
  // similar to the http4s implementation which uses kleisli as the main abstraction
  // compare Kleisli(A => F[B]) with Http(A => ZIO[R, Option[E], B])
  // both share pretty much the same signature
  val appIn: Http[Any, Nothing, Request, Response] = Http.collect[Request] {
    case Method.GET -> !!            => Response.text(s"Hello World!")
    case Method.GET -> !! / "livez"  => Response.json("""{ "status": "live"}""")
    case Method.GET -> !! / "readyz" => Response.json("""{ "status": "ready"}""")
  }

  // middleware takes an http and transforms that into another http object
  // idea is to find a no operation middleware that takes the incoming http
  // and returns it without a change but preserves the types
  // todo: maybe exists already in Http companion object
  def middlewareAb[R, E, A, B]: Middleware[R, E, A, B, A, B] = new Middleware[R, E, A, B, A, B] {
    override def apply[R1 <: R, E1 >: E](http: Http[R1, E1, A, B]): Http[R1, E1, A, B] = http
  }

  def requestLogger: Middleware[Any, Nothing, Request, Response, Request, Response] =
    middlewareAb[Any, Nothing, Request, Response].contramapZIO[Request] { req =>
      UIO(println("incoming request: " + req.toString())).as(req)
    }

  def responseLogger: Middleware[Any, Nothing, Request, Response, Request, Response] =
    middlewareAb[Any, Nothing, Request, Response].mapZIO { res =>
      UIO(println("outgoing response: " + res.toString)).as(res)
    }

  // with full control on the input and output types
  val middleware: Middleware[Any, Nothing, Request, Response, Request, Response] = requestLogger.andThen(responseLogger)

  val appOut: Http[Any, Nothing, Request, Response] = middleware(appIn)

  // looks already a bit nicer but...
  // changes the types due to all the variance annotations
  val nextTry = Middleware
    .fromHttp(appIn)
    .contramapZIO[Request] { req =>
      UIO(println("incoming request: " + req.toString())).as(req)
    }
    .mapZIO { res =>
      UIO(println("outgoing response: " + res.toString)).as(res)
    }
    .apply(appIn)

  implicit class MiddlewareOps[R, E, AIn, BIn, AOut, BOut](m: Middleware[R, E, AIn, BIn, AOut, BOut]) {
    def tapZIO[R1 <: R](effect: BOut => ZIO[R1, E, Unit]): Middleware[R1, E, AIn, BIn, AOut, BOut] =
      m.mapZIO(x => effect(x).as(x))

    def contratapZIO[R1 <: R](effect: AOut => ZIO[R1, E, Unit]): Middleware[R1, E, AIn, BIn, AOut, BOut] =
      m.contramapZIO[AOut](x => effect(x).as(x))


    //    def withRequestLogger = m >>> Middleware.requestLogger // that doesn't work yet
    //    def withResponseLogger = m >>> Middleware.responseLogger // that doesn't work yet
  }

  val nextTry2: Http[Any, Nothing, Request, Response] = Middleware
    .fromHttp(appIn)
    .contratapZIO(req => UIO(println("incoming request: " + req.toString())))
    .tapZIO(res => UIO(println("outgoing response: " + res.toString)))
    .apply(appIn)

  // now with logging
  val appWithLoggingLayer: Http[Logging, Nothing, Request, Response] = Middleware
    .fromHttp(appIn)
    .contratapZIO(req => Logging.info("incoming request: " + req.toString()))
    .tapZIO(res => Logging.info("outgoing response: " + res.toString))
    .apply(appIn)

  implicit class MiddlewareInstanceOps(unused: Middleware.type) {
    def requestLogger: Middleware[Logging, Nothing, Request, Response, Request, Response] =
      middlewareAb[Any, Nothing, Request, Response].contratapZIO { req =>
        Logging.info("incoming request: " + req.toString())
      }

    def responseLogger: Middleware[Logging, Nothing, Request, Response, Request, Response] =
      middlewareAb[Any, Nothing, Request, Response].tapZIO { res =>
        Logging.info("outgoing response: " + res.toString)
      }
  }

  val middleware123 = Middleware.requestLogger >>> Middleware.responseLogger
  val appOut123     = middleware123(appIn)

  val loggingLayer: ZLayer[Console with Clock, Nothing, Logging] =
    Logging.console() >>>
      Logging.withRootLoggerName("example-http-service")

  val server: Server[Logging, Nothing] = Server.port(8090) ++ Server.app(appWithLoggingLayer)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    server.make
      .use(start => log.info(s"Server started on port ${start.port}") *> ZIO.never)
      .provideCustomLayer(ServerChannelFactory.auto ++ EventLoopGroup.auto(4) ++ loggingLayer)
      .exitCode
}
