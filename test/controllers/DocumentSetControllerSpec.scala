/*
 * DocumentSetControllerSpec.scala
 * 
 * Overview Project
 * Created by Jonas Karlsson, June 2012
 */
package controllers

import org.specs2.matcher.JsonMatchers
import org.specs2.specification.Scope
import scala.concurrent.Future

import controllers.backend.{DocumentSetBackend,ImportJobBackend,ViewBackend}
import org.overviewproject.jobs.models.{CancelFileUpload,Delete}
import org.overviewproject.models.DocumentSet
import org.overviewproject.test.factories.PodoFactory
import org.overviewproject.tree.DocumentSetCreationJobType
import org.overviewproject.tree.DocumentSetCreationJobType._
import org.overviewproject.tree.orm.{DocumentSet=>DeprecatedDocumentSet,DocumentSetCreationJob,Tag,Tree}
import org.overviewproject.tree.orm.DocumentSetCreationJobState
import org.overviewproject.tree.orm.DocumentSetCreationJobState._
import org.overviewproject.tree.orm.finders.ResultPage

class DocumentSetControllerSpec extends ControllerSpecification with JsonMatchers {
  trait BaseScope extends Scope {
    val factory = PodoFactory

    val IndexPageSize = 10
    val mockStorage = smartMock[DocumentSetController.Storage]
    val mockJobQueue = smartMock[DocumentSetController.JobMessageQueue]
    val mockViewBackend = smartMock[ViewBackend]
    val mockBackend = smartMock[DocumentSetBackend]
    val mockImportJobBackend = smartMock[ImportJobBackend]

    val controller = new DocumentSetController {
      override val indexPageSize = IndexPageSize
      override val storage = mockStorage
      override val jobQueue = mockJobQueue
      override val backend = mockBackend
      override val importJobBackend = mockImportJobBackend
      override val viewBackend = mockViewBackend
    }

    def fakeJob(documentSetId: Long, id: Long, fileGroupId: Long,
                state: DocumentSetCreationJobState = InProgress,
                jobType: DocumentSetCreationJobType = FileUpload) = {
      DocumentSetCreationJob(
        id = id,
        documentSetId = documentSetId,
        fileGroupId = Some(fileGroupId),
        jobType = jobType,
        state = state
      )
    }
  }

  "DocumentSetController" should {
    "update" should {
      trait UpdateScope extends BaseScope {
        val documentSetId = 1
        def formBody: Seq[(String, String)] = Seq("public" -> "false", "title" -> "foo")
        def request = fakeAuthorizedRequest.withFormUrlEncodedBody(formBody: _*)
        def result = controller.update(documentSetId)(request)

        mockBackend.show(documentSetId) returns Future.successful(Some(factory.documentSet(id=documentSetId)))
      }

      "return 204" in new UpdateScope {
        h.status(result) must beEqualTo(h.NO_CONTENT)
      }

      "update the DocumentSet" in new UpdateScope {
        h.status(result) // invoke it
        there was one(mockStorage).insertOrUpdateDocumentSet(any)
      }

      "return NotFound if DocumentSet does not exist" in new UpdateScope {
        mockBackend.show(documentSetId) returns Future.successful(None)
        h.status(result) must beEqualTo(h.NOT_FOUND)
      }

      "return BadRequest if form input is bad" in new UpdateScope {
        override def formBody = Seq("public" -> "maybe", "title" -> "bar")
        h.status(result) must beEqualTo(h.BAD_REQUEST)
      }
    }

    "showHtmlInJson" should {
      trait ShowHtmlInJsonScope extends BaseScope {
        val documentSetId = 1
        def result = controller.showHtmlInJson(documentSetId)(fakeAuthorizedRequest)
        mockBackend.show(documentSetId) returns Future.successful(Some(factory.documentSet(id=documentSetId)))
        mockStorage.findNViewsByDocumentSets(Seq(documentSetId)) returns Seq(2)
      }

      "return NotFound if DocumentSet is not present" in new ShowHtmlInJsonScope {
        mockBackend.show(documentSetId) returns Future.successful(None)
        h.status(result) must beEqualTo(h.NOT_FOUND)
      }

      "return Ok if the DocumentSet exists but no trees do" in new ShowHtmlInJsonScope {
        mockStorage.findNViewsByDocumentSets(Seq(documentSetId)) returns Seq(0)
        h.status(result) must beEqualTo(h.OK)
      }

      "return Ok if the DocumentSet exists with trees" in new ShowHtmlInJsonScope {
        h.status(result) must beEqualTo(h.OK)
      }
    }

    "showJson" should {
      trait ShowJsonScope extends BaseScope {
        val documentSetId = 1
        def result = controller.showJson(documentSetId)(fakeAuthorizedRequest)

        val sampleTree = Tree(
          id = 1L,
          documentSetId = 1L,
          rootNodeId = 3L,
          jobId = 0L,
          title = "Tree title",
          documentCount = 10,
          lang = "en",
          suppliedStopWords = "",
          importantWords = ""
        )

        val sampleView = factory.view(
          title="a view"
        )

        val sampleTag = Tag(
          id=1L,
          documentSetId=1L,
          name="a tag",
          color="FFFFFF"
        )

        mockBackend.show(documentSetId) returns Future.successful(Some(factory.documentSet(documentCount=10)))
        mockStorage.findTrees(documentSetId) returns Seq(sampleTree)
        mockViewBackend.index(documentSetId) returns Future.successful(Seq(sampleView))
        mockStorage.findViewJobs(documentSetId) returns Seq()
        mockStorage.findTags(documentSetId) returns Seq(sampleTag)
      }

      "encode the count, views, tags, and search results into the json result" in new ShowJsonScope {
        h.status(result) must beEqualTo(h.OK)

        val resultJson = h.contentAsString(result)
        resultJson must /("nDocuments" -> 10)
        resultJson must /("views") */("type" -> "view")
        resultJson must /("views") */("title" -> sampleTree.title)
        resultJson must /("views") */("title" -> "a view")
        resultJson must /("tags") */("name" -> "a tag")
      }

      "include view creation jobs" in new ShowJsonScope {
        mockStorage.findTrees(sampleTree.documentSetId) returns Seq()
        mockViewBackend.index(sampleTree.documentSetId) returns Future.successful(Seq())
        mockStorage.findViewJobs(sampleTree.documentSetId) returns Seq(DocumentSetCreationJob(
          id=2L,
          documentSetId=sampleTree.documentSetId,
          treeTitle=Some("tree job"),
          jobType=DocumentSetCreationJobType.Recluster,
          state=DocumentSetCreationJobState.InProgress
        ), DocumentSetCreationJob(
          id=3L,
          documentSetId=sampleTree.documentSetId,
          treeTitle=Some("tree job"),
          jobType=DocumentSetCreationJobType.Recluster,
          state=DocumentSetCreationJobState.Error
        ))

        val json = h.contentAsString(result)

        json must /("views") /#(0) /("type" -> "job")
        json must /("views") /#(0) /("id" -> 2.0)
        json must /("views") /#(0) /("creationData") /#(0) /("lang")
        json must /("views") /#(0) /("creationData") /#(0) /("en")
        json must /("views") /#(1) /("type" -> "error")
      }
    }

    "show" should {
      trait ValidShowScope extends BaseScope {
        val documentSetId = 1L
        def request = fakeAuthorizedRequest
        lazy val result = controller.show(documentSetId)(request)
        mockBackend.show(documentSetId) returns Future.successful(Some(factory.documentSet(id=documentSetId)))
        mockImportJobBackend.index(documentSetId) returns Future.successful(Seq())
        mockImportJobBackend.indexIdsInProcessingOrder returns Future.successful(Seq())
      }

      "return NotFound when the DocumentSet is not present" in new ValidShowScope {
        mockBackend.show(documentSetId) returns Future.successful(None)
        h.status(result) must beEqualTo(h.NOT_FOUND)
      }

      "return OK when okay" in new ValidShowScope {
        h.status(result) must beEqualTo(h.OK)
        h.contentType(result) must beSome("text/html")
      }

      "show a progressbar if there is an ImportJob" in new ValidShowScope {
        val job = factory.documentSetCreationJob(fractionComplete=0.4)
        mockImportJobBackend.index(documentSetId) returns Future.successful(Seq(job))
        mockImportJobBackend.indexIdsInProcessingOrder returns Future.successful(Seq(job.id + 1, job.id + 2, job.id, job.id + 3))
        h.contentAsString(result) must beMatching("""(?s).*progress.*value="40".*""")
        h.contentAsString(result) must beMatching("""(?s).*2 jobs.*""")
      }

      "show 0 jobs ahead in the case of a race" in new ValidShowScope {
        // More of an integration test than a unit test. Oh well.
        val job = factory.documentSetCreationJob(fractionComplete=0.4)
        mockImportJobBackend.index(documentSetId) returns Future.successful(Seq(job))
        // job disappeared before we tried to find the position in queue
        mockImportJobBackend.indexIdsInProcessingOrder returns Future.successful(Seq())
        h.contentAsString(result) must beMatching("""(?s).*value="40".*""")
        h.contentAsString(result) must not(beMatching("""(?s).*-1 jobs.*"""))
      }
    }

    "index" should {
      trait IndexScope extends BaseScope {
        def pageNumber = 1

        def fakeDocumentSets: Seq[DocumentSet] = Seq(factory.documentSet(id=1L))
        mockStorage.findDocumentSets(anyString, anyInt, anyInt) answers { (_) => ResultPage(fakeDocumentSets.map(_.toDeprecatedDocumentSet), IndexPageSize, pageNumber) }
        def fakeNViews: Seq[Long] = Seq(2)
        mockStorage.findNViewsByDocumentSets(any[Seq[Long]]) answers { (_) => fakeNViews }
        def fakeJobs: Seq[(DocumentSetCreationJob, DeprecatedDocumentSet, Long)] = Seq()
        mockStorage.findDocumentSetCreationJobs(anyString) answers { (_) => fakeJobs }

        def request = fakeAuthorizedRequest

        lazy val result = controller.index(pageNumber)(request)
        lazy val j = jodd.lagarto.dom.jerry.Jerry.jerry(h.contentAsString(result))
      }

      "return Ok" in new IndexScope {
        h.status(result) must beEqualTo(h.OK)
      }

      "return Ok if there are only jobs" in new IndexScope {
        override def fakeDocumentSets = Seq()
        override def fakeJobs = Seq((fakeJob(2, 2, 2), factory.documentSet(id=2L).toDeprecatedDocumentSet, 0))

        h.status(result) must beEqualTo(h.OK)
      }

      "return Ok if there are only document sets" in new IndexScope {
        override def fakeJobs = Seq()

        h.status(result) must beEqualTo(h.OK)
      }

      "redirect to examples if there are no document sets or jobs" in new IndexScope {
        override def fakeDocumentSets = Seq()
        override def fakeJobs = Seq()

        h.status(result) must beEqualTo(h.SEE_OTHER)
      }

      "flash when redirecting" in new IndexScope {
        override def fakeDocumentSets = Seq()
        override def fakeJobs = Seq()

        override def request = super.request.withFlash("foo" -> "bar")
        h.flash(result).data must beEqualTo(Map("foo" -> "bar"))
      }

      "show page 1 if the page number is too low" in new IndexScope {
        override def pageNumber = 0
        h.status(result) must beEqualTo(h.OK) // load page
        there was one(mockStorage).findDocumentSets(anyString, anyInt, org.mockito.Matchers.eq[java.lang.Integer](1))
      }

      "show multiple pages" in new IndexScope {
        override def fakeDocumentSets = (1 until IndexPageSize * 2).map { (id) => factory.documentSet(id=id) }
        override def fakeNViews = (1 until IndexPageSize * 2).map((x: Int) => x.toLong)
        h.contentAsString(result) must contain("/documentsets?page=2")
      }

      "show multiple document sets per page" in new IndexScope {
        override def fakeDocumentSets = (1 until IndexPageSize).map { (id) => factory.documentSet(id=id) }
        override def fakeNViews = (1 until IndexPageSize * 2).map((x: Int) => x.toLong)

        h.contentAsString(result) must not contain ("/documentsets?page=2")
      }

      "bind nViews to their document sets" in new IndexScope {
        override def fakeDocumentSets = Seq(factory.documentSet(id=1L), factory.documentSet(id=2L))
        override def fakeNViews = Seq(4, 5)

        val ds1 = j.$("[data-document-set-id='1']")
        val ds2 = j.$("[data-document-set-id='2']")

        ds1.find(".view-count").text() must contain("4 views")
        ds2.find(".view-count").text() must contain("5 views")
      }

      "show jobs" in new IndexScope {
        override def fakeJobs = Seq(
          (fakeJob(2, 2, 2), factory.documentSet(2L).toDeprecatedDocumentSet, 0),
          (fakeJob(3, 3, 3), factory.documentSet(3L).toDeprecatedDocumentSet, 1)
        )

        j.$("[data-document-set-creation-job-id='2']").length must beEqualTo(1)
        j.$("[data-document-set-creation-job-id='3']").length must beEqualTo(1)
      }
    }

    "delete" should {

      trait DeleteScope extends BaseScope {
        val documentSetId = 1L
        val fileGroupId = 10L
        val documentSet = factory.documentSet(id=documentSetId)

        def request = fakeAuthorizedRequest

        lazy val result = controller.delete(documentSetId)(request)

        mockBackend.show(documentSetId) returns Future.successful(Some(documentSet))

        protected def setupJob(jobState: DocumentSetCreationJobState, jobType: DocumentSetCreationJobType = FileUpload): Unit =
          mockStorage.cancelJob(documentSetId) returns Some(fakeJob(documentSetId, 10l, fileGroupId, jobState, jobType))
      }

      "mark document set deleted and send delete request if there is no job running" in new DeleteScope {
        mockStorage.cancelJob(documentSetId) returns None

        h.status(result) must beEqualTo(h.SEE_OTHER)

        there was one(mockStorage).deleteDocumentSet(documentSet.toDeprecatedDocumentSet)
        there was one(mockJobQueue).send(Delete(documentSetId))
      }

      "mark document set and job deleted and send delete request if job has failed" in new DeleteScope {
        setupJob(Error)

        h.status(result) must beEqualTo(h.SEE_OTHER)

        there was one(mockStorage).cancelJob(documentSet.id)
        there was one(mockStorage).deleteDocumentSet(documentSet.toDeprecatedDocumentSet)
        there was one(mockJobQueue).send(Delete(documentSetId))
      }

      "mark document set and job deleted and send delete request if job is cancelled" in new DeleteScope {
        setupJob(Cancelled)

        h.status(result) must beEqualTo(h.SEE_OTHER)

        there was one(mockStorage).cancelJob(documentSet.id)
        there was one(mockStorage).deleteDocumentSet(documentSet.toDeprecatedDocumentSet)
        there was one(mockJobQueue).send(Delete(documentSetId))

      }

      "mark document set and job deleted and send delete request if import job has not started clustering" in new DeleteScope {
        setupJob(NotStarted)

        h.status(result) must beEqualTo(h.SEE_OTHER)

        there was one(mockStorage).cancelJob(documentSet.id)
        there was one(mockStorage).deleteDocumentSet(documentSet.toDeprecatedDocumentSet)
        there was one(mockJobQueue).send(Delete(documentSetId))
      }

      "mark document set and job deleted and send delete request if import job has started clustering" in new DeleteScope {
        setupJob(InProgress)

        h.status(result) must beEqualTo(h.SEE_OTHER)

        there was one(mockStorage).cancelJob(documentSet.id)
        there was one(mockStorage).deleteDocumentSet(documentSet.toDeprecatedDocumentSet)
        there was one(mockJobQueue).send(Delete(documentSetId, waitForJobRemoval = true))
      }

      "mark document set and job deleted and send cancel request if files have been uploaded" in new DeleteScope {
        setupJob(FilesUploaded)

        h.status(result) must beEqualTo(h.SEE_OTHER)

        there was one(mockStorage).cancelJob(documentSet.id)
        there was one(mockJobQueue).send(CancelFileUpload(documentSetId, fileGroupId))
      }

      "mark document set and job deleted and send cancel request if text extraction in progress" in new DeleteScope {
        setupJob(TextExtractionInProgress)

        h.status(result) must beEqualTo(h.SEE_OTHER)

        there was one(mockStorage).cancelJob(documentSet.id)
        there was one(mockJobQueue).send(CancelFileUpload(documentSetId, fileGroupId))
      }
    }
  }
}
