package org.overviewproject.background.filecleanup

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.overviewproject.models.tables.Files
import org.overviewproject.database.HasDatabase

/**
 * Looks for [[File]]s with `referenceCount == 0`
 */
trait DeletedFileFinder extends HasDatabase {
  import database.api._
  
  def deletedFileIds: Future[Iterable[Long]] = database.seq(Files.filter(_.referenceCount === 0).map(_.id))
}

object DeletedFileFinder extends DeletedFileFinder
