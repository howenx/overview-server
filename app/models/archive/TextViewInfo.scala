package models.archive

import java.nio.charset.StandardCharsets
import play.api.libs.iteratee.Enumerator
import scala.concurrent.{Future,blocking}

import org.overviewproject.database.HasDatabase

abstract class TextViewInfo(
  val suppliedId: String,
  val title: String,
  val documentId: Long,
  val pageNumber: Option[Int],
  override val size: Long
) extends DocumentViewInfo {
  override def name = {
    val basename = Seq(suppliedId, title)
      .find(!_.isEmpty)
      .getOrElse(documentId.toString)

    asTxt(maybeAddPageNumber(basename))
  }

  private def asTxt(filename: String): String = {
    val Txt = ".txt"
    filename + Txt
  }

  private def maybeAddPageNumber(filename: String): String =
    pageNumber.map(addPageNumber(filename, _))
      .getOrElse(filename)

  override def equals(o: Any) = o match {
    case rhs: TextViewInfo => (
      suppliedId == rhs.suppliedId
      && title == rhs.title
      && documentId == rhs.documentId
      && pageNumber == rhs.pageNumber
      && size == rhs.size
    )
    case _ => false
  }
}

object TextViewInfo {
  def apply(suppliedId: String, title: String, documentId: Long, pageNumber: Option[Int], size: Long): TextViewInfo =
    new DbTextViewInfo(suppliedId, title, documentId, pageNumber, size)

  private class DbTextViewInfo(suppliedId: String, title: String, documentId: Long, pageNumber: Option[Int], size: Long)
      extends TextViewInfo(suppliedId, title, documentId, pageNumber, size)
      with HasDatabase
  {

    override def stream = {
      import database.api._
      import database.executionContext
      import org.overviewproject.models.tables.Documents

      for {
        maybeString: Option[String] <- database.option(Documents.filter(_.id === documentId).map(_.text.getOrElse("")))
      } yield Enumerator(maybeString.getOrElse("").getBytes(StandardCharsets.UTF_8))
    }
  }
}
