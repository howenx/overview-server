package org.overviewproject.tree.orm

import java.sql.Timestamp
import org.squeryl.KeyedEntity
import org.overviewproject.models.{DocumentInfo,Document => BetterDocument}
import org.overviewproject.models.DocumentDisplayMethod.DocumentDisplayMethod

case class Document(
  val documentSetId: Long = 0L,
  val description: String = "",
  val title: Option[String] = None,
  val suppliedId: Option[String] = None,
  val text: Option[String] = None,
  val url: Option[String] = None,
  val documentcloudId: Option[String] = None,
  val createdAt: Timestamp = new Timestamp(0),
  val fileId: Option[Long] = None,
  val pageId: Option[Long] = None,
  val pageNumber: Option[Int] = None,
  override val id: Long = 0L) extends KeyedEntity[Long] with DocumentSetComponent {

  // https://www.assembla.com/spaces/squeryl/tickets/68-add-support-for-full-updates-on-immutable-case-classes#/followers/ticket:68
  override def isPersisted(): Boolean = (id > 0)

  private val pathDelimiterIndex = this.title.getOrElse("").lastIndexOf("/")

  val (folderPath: Option[String], titleProper:Option[String]) = if(pathDelimiterIndex != -1) {
    ("/" + this.title.get).splitAt(pathDelimiterIndex + 1) match {
      case (a, b) => (Some(a), Some(b.drop(1)))
    }
  } else {
    (None, this.title)
  }

  def toDocumentInfo = DocumentInfo(
    id=id,
    documentSetId=documentSetId,
    url=url,
    suppliedId=suppliedId.orElse(documentcloudId).getOrElse(""),
    title=title.getOrElse(""),
    pageNumber=pageNumber,
    keywords=description.split(" "),
    createdAt=createdAt,
    displayMethod=None,
    fileId.isDefined
  )

  def toDocument = BetterDocument(
    id=id,
    documentSetId=documentSetId,
    url=url,
    suppliedId=suppliedId.orElse(documentcloudId).getOrElse(""),
    title=title.getOrElse(""),
    pageNumber=pageNumber,
    keywords=description.split(" "),
    createdAt=createdAt,
    fileId=fileId,
    pageId=pageId,
    displayMethod=None,
    text=text.getOrElse("")
  )
}

