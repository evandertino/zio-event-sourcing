package zio.es.storage

import java.util.UUID

import com.datastax.driver.core.utils.UUIDs
import zio.{ Task, ZIO }
import zio.es._
import zio.es.serializers.protobuf._
import zio.duration._
import zio.test.Assertion._
import zio.test._
import zio.test.TestAspect._

//noinspection TypeAnnotation
object CassandraStorageSpec extends DefaultRunnableSpec {
  implicit val serializer: SerializableEvent[JournalTestModel] = PBSerializer.serializer[JournalTestModel]

  private val serializationTest = test("Should find implicit serializer for event") {
    val item         = JournalTestModel("id-1", block = false)
    val ser          = implicitly[SerializableEvent[JournalTestModel]]
    val serBytes     = ser.toBytes(item)
    val deserialized = ser.fromBytes(serBytes)
    assert(deserialized)(equalTo(item))
  }

  private def buildTestAggregate: Task[AggregateBehaviour[JournalTestModel, Seq[JournalTestModel]]] =
    EventJournal.aggregate[JournalTestModel, Seq[JournalTestModel]](Seq.empty[JournalTestModel]) {
      case (s, e) => ZIO.effect(s :+ e)
    }

  private val createEmpty: ZSpec[Any, Throwable] = suite("creates empty Aggregate") {
    testM("creates empty Aggregate") {
      tmpTestStore.use { store =>
        for {
          testAggregate <- buildTestAggregate
          created       <- store.create(UUID.randomUUID().toString, testAggregate)
          createdState  <- created.state
        } yield assert(createdState)(hasSize(equalTo(0)))
      }
    }
  }

  private val saveAndLoad: ZSpec[Any, Throwable] = testM("check batch-loaded and realtime-loaded states ase same") {
    val eventsSeq = Seq(
      JournalTestModel("id-1", block = false),
      JournalTestModel("id-2", block = true),
      JournalTestModel("id-3", block = true)
    )

    val entityId = UUID.randomUUID().toString
    tmpTestStore.use { store =>
      for {
        testAggregate <- buildTestAggregate
        aggregate     <- store.load[Seq[JournalTestModel]](entityId, testAggregate)
        _             <- aggregate.appendAll(eventsSeq)
        createdState  <- aggregate.state
        loaded        <- store.load(entityId, testAggregate)
        loadedState   <- loaded.state
      } yield {
        assert(createdState)(hasSameElements(eventsSeq)) &&
        assert(loadedState)(hasSameElements(eventsSeq))
      }
    }
  }

  private def tmpTestStore = {
    val testKeyspaceName = ("zio_es_test_" + UUIDs.timeBased()).replace("-", "_")
    CassandraStorage.connect(
      servers = Seq("localhost"),
      keyspace = testKeyspaceName,
      table = "events",
      replicationFactor = 1,
      destroyAfterStop = true
    )
  }

  private val timeoutDuration = 5.second

  def spec: ZSpec[CassandraStorageSpec.Environment, CassandraStorageSpec.Failure] =
    suite("CassandraStorage specs")(
      serializationTest @@ timeout(timeoutDuration),
      createEmpty @@ timeout(timeoutDuration),
      saveAndLoad @@ timeout(timeoutDuration)
    ) @@ parallel @@ ignore
}
