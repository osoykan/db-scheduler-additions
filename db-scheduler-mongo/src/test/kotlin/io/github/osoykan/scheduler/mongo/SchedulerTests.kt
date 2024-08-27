package io.github.osoykan.scheduler.mongo

import arrow.atomic.AtomicInt
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.mongodb.*
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.common.ExperimentalKotest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldNotBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.bson.UuidRepresentation
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class TestConfig : AbstractProjectConfig() {
  @ExperimentalKotest
  override val concurrentTests: Int = 10
}

class SchedulerTests : FunSpec({
  lateinit var mongo: Mongo
  lateinit var client: MongoClient
  val mongoDbName = "db-scheduler"
  beforeSpec {
    val mongodb = MongoDBContainer(DockerImageName.parse("mongo:latest")).apply {
      portBindings = listOf("27017:27017")
    }
    mongodb.start()

    println("MongoDB URL: ${mongodb.replicaSetUrl}")

    val settings = MongoClientSettings
      .builder()
      .applyConnectionString(ConnectionString(mongodb.connectionString))
      .uuidRepresentation(UuidRepresentation.STANDARD)
      .readConcern(ReadConcern.MAJORITY)
      .codecRegistry(
        PojoRegistry()
          .register<TaskEntity>()
          .build()
      )
    client = MongoClient.create(settings.build())
    mongo = Mongo(client, mongoDbName)
  }

  test("should work") {
    val testMongo = mongo.copy(collection = testCase.name.testName)
      .also { it.ensureCollectionExists() }

    var invoked = 0
    val oneTimeTask = Tasks.oneTime("one-time-task")
      .execute { _, _ ->
        invoked++
        println("Executing one-time task")
      }

    val scheduler = MongoScheduler.create(testMongo, listOf(oneTimeTask), name = testCase.name.testName.take(10))
    scheduler.start()
    scheduler.schedule(oneTimeTask.instance("1"), Instant.now())

    eventually(10.seconds) {
      invoked shouldBe 1
    }

    scheduler.stop()
  }

  test("schedule 50 tasks") {
    val testMongo = mongo.copy(collection = testCase.name.testName)
      .also { it.ensureCollectionExists() }

    data class TestTaskData(val name: String)

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("50Tasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = MongoScheduler.create(testMongo, listOf(task), name = testCase.name.testName.take(10))
    scheduler.start()

    val time = Instant.now()

    (1..50).map { i -> async { scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()
    eventually(30.seconds) {
      executionCount.get() shouldBe 50
      executionCount.get() shouldNotBeGreaterThan 50
    }
  }

  test("Recurring Task") {
    val testMongo = mongo.copy(collection = testCase.name.testName)
      .also { it.ensureCollectionExists() }

    data class TestTaskData(val name: String)

    val executionCount = AtomicInt(0)
    val task = Tasks.recurring("A Recurring Task", Schedules.fixedDelay(2.seconds.toJavaDuration()), TestTaskData::class.java)
      .initialData(TestTaskData("test"))
      .execute { _, _ ->
        executionCount.incrementAndGet()
      }

    val scheduler = MongoScheduler.create(testMongo, startupTasks = listOf(task), name = testCase.name.testName.take(10))
    scheduler.start()

    eventually(10.seconds) {
      executionCount.get() shouldBe 2
      executionCount.get() shouldNotBeGreaterThan 2
    }
  }

  test("multiple schedulers racing for oneTimeTasks") {
    val testMongo = mongo.copy(collection = testCase.name.testName)
      .also { it.ensureCollectionExists() }

    data class TestTaskData(val name: String)

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("200Tasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = MongoScheduler.create(testMongo, name = testCase.name.testName.take(10))
    val amount = 200
    val time = Instant.now()
    val tasks = (1..amount)
    runBlocking {
      tasks.map { i -> async { scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()
    }

    val scheduler1 = MongoScheduler.create(testMongo, listOf(task), name = testCase.name.testName.take(10) + "racer 1", fixedThreadPoolSize = 10)
    val scheduler2 = MongoScheduler.create(testMongo, listOf(task), name = testCase.name.testName.take(10) + "racer 2", fixedThreadPoolSize = 10)
    val scheduler3 = MongoScheduler.create(testMongo, listOf(task), name = testCase.name.testName.take(10) + "racer 3", fixedThreadPoolSize = 15)
    awaitAll(
      async { scheduler1.start() },
      async { scheduler2.start() },
      async { scheduler3.start() }
    )
    eventually(30.seconds) {
      executionCount.get() shouldBe amount
      executionCount.get() shouldNotBeGreaterThan amount
    }

    scheduler1.stop()
    scheduler2.stop()
    scheduler3.stop()
  }
})
