package org.overviewproject.blobstorage

import java.io.{ByteArrayInputStream,IOException,InputStream}
import org.postgresql.util.PSQLException
import play.api.libs.iteratee.Iteratee
import scala.util.{Failure,Success}

import org.overviewproject.database.{LargeObject}
import org.overviewproject.test.DbSpecification

class PgLoStrategySpec extends DbSpecification with StrategySpecification {
  trait PgLoBaseScope extends DbScope {
    import database.api._

    val loManager = database.largeObjectManager

    // Each test runs in a transaction. Make sure the _code_ uses the same
    // connection, even though it's in a Future.
    object TestStrategy extends PgLoStrategy {
      override protected val BufferSize = 10 // to prove we paginate
      override protected val DeleteManyChunkSize = 2
    }

    def createLargeObject(data: Array[Byte]): Long = blockingDatabase.run((for {
      oid <- loManager.create
      lo <- loManager.open(oid, LargeObject.Mode.Write)
      _ <- lo.write(data)
    } yield oid).transactionally)

    def readLargeObject(loid: Long): Array[Byte] = blockingDatabase.run((for {
      lo <- loManager.open(loid, LargeObject.Mode.Read)
      bytes <- lo.read(9999)
    } yield bytes).transactionally)

    def largeObjectExists(loid: Long): Boolean = {
      val SqlStateUndefinedObject = "42704" // http://www.postgresql.org/docs/9.3/static/errcodes-appendix.html
      val result = blockingDatabase.run((for {
        lo <- loManager.open(loid, LargeObject.Mode.Read)
      } yield ()).transactionally.asTry)
      result match {
        case Success(()) => true
        case Failure(e: PSQLException) if (e.getSQLState() == SqlStateUndefinedObject) => false
        case Failure(t) => throw t
      }
    }
  }

  "#get" should {
    trait GetScope extends PgLoBaseScope

    "throw an exception when location does not look like pglo:12345" in new GetScope {
      TestStrategy.get("pglo") must throwA[IllegalArgumentException]
      TestStrategy.get("pglo:abcd") must throwA[IllegalArgumentException]
      TestStrategy.get("pglo:18446744073709552001") must throwA[IllegalArgumentException]
    }

    "throw a delayed exception when the location points at an invalid large object" in new GetScope {
      await(TestStrategy.get("pglo:123")) must throwA[Exception]
    }

    "return an Enumerator of the file" in new GetScope {
      val data = "foo".getBytes("utf-8")
      val loid = createLargeObject(data)
      val enumerator = await(TestStrategy.get("pglo:" + loid))
      await(enumerator.run(Iteratee.consume())) must beEqualTo(data)
    }

    "fill the buffer multiple times in the Enumerator if necessary" in new GetScope {
      val data = "foo bar baz moo mar maz".getBytes("utf-8")
      val loid = createLargeObject(data)
      val enumerator = await(TestStrategy.get("pglo:" + loid))
      await(enumerator.run(Iteratee.consume())) must beEqualTo(data)
    }
  }

  "#delete" should {
    trait DeleteScope extends PgLoBaseScope

    "throw an exception when location does not look like pglo:12345" in new DeleteScope {
      TestStrategy.delete("pglo") must throwA[IllegalArgumentException]
      TestStrategy.delete("pglo:abcd") must throwA[IllegalArgumentException]
      TestStrategy.delete("pglo:18446744073709552001") must throwA[IllegalArgumentException]
    }

    "succeed when the large object does not exist" in new DeleteScope {
      await(TestStrategy.delete("pglo:123")) must beEqualTo(())
    }

    "delete the blob when the large object exists" in new DeleteScope {
      val data = "foo".getBytes("utf-8")
      val loid = createLargeObject(data)
      await(TestStrategy.delete("pglo:" + loid)) must beEqualTo(())
      largeObjectExists(loid) must beFalse
    }
  }

  "#deleteMany" should {
    trait DeleteManyScope extends PgLoBaseScope

    "throw an exception when location does not look like pglo:12345" in new DeleteManyScope {
      TestStrategy.deleteMany(Seq("pglo:123", "pglo")) must throwA[IllegalArgumentException]
      TestStrategy.deleteMany(Seq("pglo:123", "pglo:abcd")) must throwA[IllegalArgumentException]
      TestStrategy.deleteMany(Seq("pglo:123", "pglo:18446744073709552001")) must throwA[IllegalArgumentException]
    }

    "delete large objects" in new DeleteManyScope {
      val loid1 = createLargeObject("foo".getBytes("utf-8"))
      val loid2 = createLargeObject("bar".getBytes("utf-8"))
      await(TestStrategy.deleteMany(Seq("pglo:" + loid1, "pglo:" + loid2))) must beEqualTo(())
      // largeObjectExists(loid1) must beEqualTo(false)
      largeObjectExists(loid2) must beEqualTo(false) // largeObjectExists() breaks if called twice
    }

    "delete even when a large object does not exist" in new DeleteManyScope {
      val loid1 = createLargeObject("foo".getBytes("utf-8"))
      val loid2 = loid1 + 1;
      val loid3 = createLargeObject("bar".getBytes("utf-8"))
      await(TestStrategy.deleteMany(Seq("pglo:" + loid1, "pglo:" + loid2, "pglo:" + loid3))) must beEqualTo(())
      // largeObjectExists(loid1) must beEqualTo(false)
      largeObjectExists(loid3) must beEqualTo(false) // largeObjectExists() breaks if called twice
    }
    
    "delete many large objects in chunks" in new DeleteManyScope {
      val loids = Seq.fill(3)(createLargeObject("foo".getBytes("utf-8")))
      val loLocations = loids.map("pglo:" + _)
      
      await(TestStrategy.deleteMany(loLocations))
      
      largeObjectExists(loids.last) must beFalse  
    }
  }

  "#create" should {
    trait CreateScope extends PgLoBaseScope {
      val data = "foo bar baz moo mar maz".getBytes("utf-8")
      val dataAsStream = new ByteArrayInputStream(data)
      def go = TestStrategy.create("pglo", dataAsStream, data.length)
    }

    "throw an exception when location prefix is not pglo" in new CreateScope {
      TestStrategy.create("pglop", dataAsStream, data.length) must throwA[IllegalArgumentException]
      TestStrategy.create("pglo:", dataAsStream, data.length) must throwA[IllegalArgumentException]
      TestStrategy.create("pgl", dataAsStream, data.length) must throwA[IllegalArgumentException]
    }

    "return a valid location" in new CreateScope {
      await(go) must beMatching("^pglo:\\d+$".r)
    }

    "store the file" in new CreateScope {
      val location = await(go)
      val loid = location.split(":")(1).toLong
      readLargeObject(loid) must beEqualTo(data)
    }

    "fail when storing the file fails" in new CreateScope {
      val evilStream = new InputStream {
        override def read = throw new IOException("this method intentionally left broken")
      }
      await(TestStrategy.create("pglo", evilStream, 10)) must throwA[IOException]
    }
  }
}
