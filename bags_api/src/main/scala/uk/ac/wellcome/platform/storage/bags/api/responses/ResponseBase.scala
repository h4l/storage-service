package uk.ac.wellcome.platform.storage.bags.api.responses

import java.net.URL
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import uk.ac.wellcome.json.JsonUtil._
import io.circe.Printer
import weco.http.json.DisplayJsonUtil
import weco.http.models.{ContextResponse, DisplayError}

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait ResponseBase {
  implicit val printer: Printer =
    DisplayJsonUtil.printer

  val contextURL: URL

  protected def withFuture(future: Future[Route]): Route =
    onComplete(future) {
      case Success(resp) => resp
      case Failure(_) =>
        complete(
          StatusCodes.InternalServerError -> ContextResponse(
            context = contextURL,
            DisplayError(statusCode = StatusCodes.InternalServerError)
          )
        )
    }
}
