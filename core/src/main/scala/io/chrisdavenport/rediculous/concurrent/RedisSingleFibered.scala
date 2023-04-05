package io.chrisdavenport.rediculous.concurrent

import cats._
import io.chrisdavenport.rediculous.RedisConnection
import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.std.UUIDGen
import scala.concurrent.duration._
import io.chrisdavenport.rediculous.RedisCommands
import io.circe._
import io.chrisdavenport.rediculous._


object RedisSingleFibered {

  // State of the Deferred Location
  sealed trait SingleFiberedState[A]
  object SingleFiberedState {

    case class Canceled[A]() extends SingleFiberedState[A]
    case class Completed[A](maybeValue: Option[A]) extends SingleFiberedState[A]
    case class Errored[A](message: String) extends SingleFiberedState[A]

    import io.circe.syntax._
    implicit def encoder[A: Encoder]: Encoder[SingleFiberedState[A]] = Encoder.instance[SingleFiberedState[A]]{
      case Canceled() => Json.obj("state" -> "canceled".asJson)
      case Errored(message) => Json.obj("state" -> "errored".asJson, "message" -> message.asJson)
      case Completed(maybeValue) =>
        Json.obj("state" -> "completed".asJson, "value" -> maybeValue.asJson)

    }

    implicit def decoder[A: Decoder]: Decoder[SingleFiberedState[A]] = Decoder.instance{
      h =>

      h.downField("state").as[String].flatMap{
        case "canceled" => Canceled[A]().pure[Either[DecodingFailure, *]]
        case "errored" => h.downField("message").as[String].map(Errored[A](_))
        case "completed" => h.downField("value").as[Option[A]].map(Completed(_))
      }
    }
  }

  /**
   *
   **/
  def redisSingleFibered[F[_]: Async: UUIDGen, V: Decoder: Encoder](
    connection: RedisConnection[F],
    keyLocation: String,

    maximumActionDuration: FiniteDuration, // This should be the maximum time that the action should take

    acquireTimeoutKeyLocationLock: FiniteDuration,
    timeoutKeyLocationLock: FiniteDuration,

    pollingIntervalForCompletion: FiniteDuration,
    deferredNameSpace: String = "deferred",
  )(
    action: F[V],
  ): F[V] = {
    // val lifetimeDeferredSet = 2 * timeoutKeyLocationLock
    def loop = redisSingleFibered(connection, keyLocation, maximumActionDuration, acquireTimeoutKeyLocationLock, timeoutKeyLocationLock, pollingIntervalForCompletion, deferredNameSpace)(action)

    def fromDeferredLocation(key: String) =
      RedisDeferred.fromKey(connection, key, pollingIntervalForCompletion, timeoutKeyLocationLock)
        .get
        .flatMap(s => io.circe.parser.parse(s).liftTo[F])
        .flatMap(json => json.as[SingleFiberedState[V]].liftTo[F])
        .flatMap{
          case SingleFiberedState.Canceled() => new RuntimeException("RedisSingleFibered Remote Action Cancelled").raiseError[F, V]
          case SingleFiberedState.Errored(message) => new RuntimeException(s"RedisSingleFibered Remote Action Failed: $message").raiseError[F, V]
          case SingleFiberedState.Completed(None) => new RuntimeException(s"RedisSingleFibered Remote Action Did Not Return Value: Did you use a monad transformer that does not return a value as a part of a succes condition?").raiseError[F, V]
          case SingleFiberedState.Completed(Some(value)) =>
            // println(s"Received Value: key: $key value:$value")
            value.pure[F]
        }.timeout(maximumActionDuration)

    def encodeOutcome(outcome: Outcome[F, Throwable, V]): F[String] = outcome match {
      case Outcome.Canceled() =>
        val state: SingleFiberedState[V] = SingleFiberedState.Canceled[V]()
        SingleFiberedState.encoder[V].apply(state).noSpaces.pure[F]
      case Outcome.Errored(e) =>
        val state : SingleFiberedState[V] = SingleFiberedState.Errored(Option(e.toString).getOrElse("Null returned for the SingleFiberedState error toString"))
        SingleFiberedState.encoder[V].apply(state).noSpaces.pure[F]
      case Outcome.Succeeded(fa) =>
        Ref[F].of[Option[V]](None).flatMap{ref =>
          fa.flatMap{ v =>
            ref.set(Some(v))
          } >>
          ref.get.map{
            v =>
              val state: SingleFiberedState[V] = SingleFiberedState.Completed(v)
              SingleFiberedState.encoder[V].apply(state).noSpaces
          }
        }
    }

    def writeMaybe: Resource[F, Boolean] =
      RedisLock.tryAcquireLockWithTimeout(
        connection,
        keyLocation,
        acquireTimeout = acquireTimeoutKeyLocationLock,
        lockTimeout = timeoutKeyLocationLock,
      )

    UUIDGen[F].randomUUID.flatMap{ case id =>
      val key = s"$deferredNameSpace:$id"
      RedisCommands.get(keyLocation).run(connection).flatMap{
        case Some(value) =>
          // println(s"Waiting for deferred location $value")
          fromDeferredLocation(value)
        case None =>
          Clock[F].realTime.flatMap{ start =>
            Concurrent[F].uncancelable(poll =>
              writeMaybe.use{
                case true =>
                  Clock[F].realTime.flatMap{ newTime =>
                    val elapsed = newTime - start
                    val setOpts = RedisCommands.SetOpts(
                      setSeconds = None,
                      setMilliseconds = Some((maximumActionDuration - elapsed).toMillis),
                      setCondition = Some(RedisCommands.Condition.Nx),
                      keepTTL = false
                    )
                    RedisCommands.set(keyLocation, key, setOpts).run(connection).map{
                      case Some(value) => true // We set the value so own the action
                      case None => false // Something was set by an intermediate lock, loop
                    }
                  }
                case false => Concurrent[F].pure(false)
              }.flatMap{
                case true =>
                  val deferred = RedisDeferred.fromKey(connection, key, pollingIntervalForCompletion, maximumActionDuration)
                  Clock[F].realTime.flatMap{ newTime =>
                    val elapsed = newTime - start
                    val rest = maximumActionDuration - elapsed
                    poll(action.timeout(rest))
                      .guaranteeCase{ outcome =>
                        encodeOutcome(outcome).flatMap{ out =>
                          Clock[F].realTime.flatMap{ endTime =>
                            val elapsed = endTime - start
                            val isExpired = maximumActionDuration - elapsed < 0.millis
                            RedisCommands.del(key).run(connection).whenA(!isExpired) >> deferred.complete(out).void //  <* Applicative[F].unit.map(_ => println(s"Set $key to $out"))
                          }
                        }
                      }
                  }
                case false => poll(loop)
              }
            )
          }
      }
    }
  }

  def redisSingleFiberedFunction[F[_]: Async: UUIDGen, K, V: Decoder: Encoder](
    connection: RedisConnection[F],
    baseKeyLocation: String,

    keyLocationTimeout: FiniteDuration,
    acquireTimeoutKeyLocationLock: FiniteDuration,
    timeoutKeyLocationLock: FiniteDuration,


    pollingIntervalDeferred: FiniteDuration,
    deferredNameSpace: String = "deferred",
  )(
    encodeKey: K => String,
    action: K => F[V],
  ): K => F[V] = {(k: K) =>
    redisSingleFibered[F, V](
      connection,
      s"$baseKeyLocation:${encodeKey(k)}",
      keyLocationTimeout,
      acquireTimeoutKeyLocationLock,
      timeoutKeyLocationLock,
      pollingIntervalDeferred,
      s"$deferredNameSpace:${encodeKey(k)}"
    )(action(k))
  }
}