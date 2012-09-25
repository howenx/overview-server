package views.json.DocumentSet

import models.orm.{ DocumentSet, DocumentSetCreationJob }
import models.orm.DocumentSetCreationJobState._
import models.orm.DocumentSetCreationJobStateDescription
import play.api.i18n.Lang
import play.api.libs.json.JsValue
import play.api.libs.json.Json.toJson

object show {
  private def m(key: String, args: Any*) = {
    play.api.i18n.Messages("views.DocumentSet._documentSet." + key, args: _*)
  }

  private val JobStateDescriptionKey = "job_state_description."
  private val JobStateKey = "job_state."

  private def stateDescription(description: String): String = {
    try {
      m(DocumentSetCreationJobStateDescription.withName(description).toString)
    } catch {
      case e: NoSuchElementException => ""
    }
  }

  private def documentSetCreationJobProperties(job: DocumentSetCreationJob)(implicit lang: Lang) = {
    val notCompleteMap = Map(
      "state" -> toJson(m(JobStateKey + job.state.toString)),
      "percent_complete" -> toJson(math.round(job.fractionComplete * 100)),
      "state_description" -> toJson(stateDescription(job.stateDescription)))
    val notStartedMap = job.state match {
      case NotStarted => Map("n_jobs_ahead_in_queue" -> toJson(job.jobsAheadInQueue))
      case _ => Map()
    }

    notCompleteMap ++ notStartedMap
  }

  private[DocumentSet] def documentSetProperties(documentSet: DocumentSet)(implicit lang: Lang) = {
    Map(
      "html" -> toJson(views.html.DocumentSet._documentSet(documentSet).toString))
  }

  private[DocumentSet] implicit def documentSetToJson(documentSet: DocumentSet): JsValue = {
    val documentSetMap = Map(
      "id" -> toJson(documentSet.id),
      "query" -> toJson(documentSet.query))

    val jobStatusMap = documentSet.documentSetCreationJob match {
      case Some(documentSetCreationJob) => documentSetCreationJobProperties(documentSetCreationJob)
      case None => documentSetProperties(documentSet)
    }

    toJson(documentSetMap ++ jobStatusMap)
  }

  def apply(documentSet: DocumentSet): JsValue = {
    documentSetToJson(documentSet)
  }
}
