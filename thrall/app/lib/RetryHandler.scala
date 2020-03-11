package lib


import akka.actor.ActorSystem
import akka.pattern.{after, retry}
import com.gu.mediaservice.lib.logging.MarkerAugmentation
import play.api.{Logger, MarkerContext}

import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object RetryHandler {
  type Runner[T] = (MarkerContext) => Future[T]

  def handleWithRetryAndTimeout[T](f: Runner[T],
                                   retries: Int,
                                   timeout: FiniteDuration,
                                   delay: FiniteDuration,
                                   markerContext: MarkerContext
                                  )(implicit actorSystem: ActorSystem,
                                    executionContext: ExecutionContext,
                                  ): Future[T] = {
    def logFailures[T](f: Runner[T]): Runner[T] = {
      (mc) => {
        f(mc).transform {
          case Success(x) => Success(x)
          case Failure(t: TimeoutException) => {
            Logger.error("Failed with timeout. Will retry")
            Failure(t)
          }
          case Failure(exception) => {
            Logger.error("Failed with exception.", exception)
            Failure(exception)
          }
        }
      }
    }

    def handleWithTimeout[T](f: Runner[T], attemptTimeout: FiniteDuration): Runner[T] = (mc) => {
      val timeout = after(attemptTimeout, using = actorSystem.scheduler)(Future.failed(
        new TimeoutException(s"Timeout of $attemptTimeout reached.")
      ))
      Future.firstCompletedOf(Seq(timeout, f(mc)))
    }

    def handleWithRetry[T](f: Runner[T], retries: Int, delay: FiniteDuration): Runner[T] = (mc) => {
      implicit val scheduler = actorSystem.scheduler
      var count = 0

      def attempt = () => {
        count = count + 1
        val markerContextWithRetry =  MarkerAugmentation.augmentMarkerContext(mc, "retryCount" -> count)
        Logger.info(s"Attempt $count of $retries")(markerContextWithRetry)
        f(markerContextWithRetry)
      }
      retry(attempt, retries, delay)
    }
    handleWithRetry(handleWithTimeout(logFailures(f), timeout), retries, delay)(markerContext)
  }
}
