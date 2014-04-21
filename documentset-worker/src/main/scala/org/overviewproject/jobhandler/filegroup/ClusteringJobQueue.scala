package org.overviewproject.jobhandler.filegroup

import akka.actor.Actor
import org.overviewproject.jobhandler.filegroup.ClusteringJobQueueProtocol.ClusterDocumentSet
import org.overviewproject.database.Database
import org.overviewproject.database.orm.finders.DocumentSetCreationJobFinder
import org.overviewproject.tree.orm.DocumentSetCreationJobState._
import org.overviewproject.database.orm.stores.DocumentSetCreationJobStore
import akka.actor.Props

trait ClusteringJobQueue extends Actor {

  protected val storage: Storage

  trait Storage {
    def submitJob(documentSetId: Long): Unit
  }

  def receive = {
    case ClusterDocumentSet(documentSetId) => storage.submitJob(documentSetId)
  }
}

class ClusteringJobQueueImpl extends ClusteringJobQueue {

  override protected val storage: Storage = new DatabaseStorage

  class DatabaseStorage extends Storage {
    override def submitJob(documentSetId: Long) = Database.inTransaction {
      val job = DocumentSetCreationJobFinder.byDocumentSetAndState(documentSetId, Preparing).headOption
      job.map { j =>
        DocumentSetCreationJobStore.insertOrUpdate(j.copy(state = NotStarted))
      }
    }
  }
}

object ClusteringJobQueue {

  def apply(): Props = Props[ClusteringJobQueueImpl]
}