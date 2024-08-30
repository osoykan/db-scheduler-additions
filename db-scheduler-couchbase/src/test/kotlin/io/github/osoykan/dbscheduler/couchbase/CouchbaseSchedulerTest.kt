package io.github.osoykan.dbscheduler.couchbase

import arrow.atomic.AtomicInt
import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.codec.*
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.github.osoykan.dbscheduler.common.ARandom
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldNotBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.testcontainers.couchbase.*
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class TestTaskData(val name: String)

private const val DEFAULT_BUCKET = "db-scheduler"

class CouchbaseSchedulerTest : FunSpec({
  val container = CouchbaseContainer(DockerImageName.parse("couchbase/server:7.6.2"))
    .apply {
      withBucket(BucketDefinition(DEFAULT_BUCKET))
      withEnabledServices(CouchbaseService.KV, CouchbaseService.QUERY, CouchbaseService.INDEX)
      withStartupAttempts(3)
    }

  lateinit var cluster: Cluster
  lateinit var couchbase: Couchbase

  beforeSpec {
    container.start()
    val connectionString = container.connectionString
    val username = container.username
    val password = container.password
    cluster = Cluster.connect(connectionString, username, password) {
      timeout {
        this.kvTimeout = 30.seconds
        this.queryTimeout = 30.seconds
        this.viewTimeout = 30.seconds
        this.connectTimeout = 30.seconds
        this.disconnectTimeout = 30.seconds
        this.kvDurableTimeout = 30.seconds
        this.kvScanTimeout = 30.seconds
        this.searchTimeout = 30.seconds
      }
      compression { this.enable = true }
      this.jsonSerializer = JacksonJsonSerializer(CouchbaseScheduler.defaultObjectMapper)
      this.transcoder = JsonTranscoder(JacksonJsonSerializer(CouchbaseScheduler.defaultObjectMapper))
    }.also { it.waitUntilReady(30.seconds) }

    cluster.waitUntilReady(30.seconds)
    couchbase = Couchbase(cluster, DEFAULT_BUCKET)
  }

  afterSpec {
    container.stop()
  }

  test("scheduler should be started") {
    val collection = ARandom.text()
    val testCouchbase = couchbase.copy(preferredCollection = collection).also {
      it.ensurePreferredCollectionExists()
    }

    val scheduler = CouchbaseScheduler.create(testCouchbase, knownTasks = listOf(), name = testCase.name.testName)
      .also { it.start() }

    scheduler.schedulerState.isStarted shouldBe true
    scheduler.stop()
  }

  test("should schedule a task") {
    val collection = ARandom.text()
    val testCouchbase = couchbase.copy(preferredCollection = collection).also {
      it.ensurePreferredCollectionExists()
    }

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("A One Time Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = CouchbaseScheduler.create(testCouchbase, knownTasks = listOf(task), name = testCase.name.testName)
      .also { it.start() }

    scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test")), Instant.now().plusMillis(200))

    eventually(30.seconds) {
      executionCount.get() shouldBe 1
      executionCount.get() shouldNotBeGreaterThan 1
    }
    scheduler.stop()
  }

  test("schedule 50 tasks") {
    val collection = ARandom.text()
    val testCouchbase = couchbase.copy(preferredCollection = collection).also {
      it.ensurePreferredCollectionExists()
    }

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("50Tasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val amountOfTasks = 50
    val tasks = (1..amountOfTasks)
    val time = Instant.now()
    val scheduler = CouchbaseScheduler.create(testCouchbase, knownTasks = listOf(task), name = testCase.name.testName)
      .also { it.start() }

    tasks.map { i -> async { scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()

    eventually(30.seconds) {
      executionCount.get() shouldBe amountOfTasks
      executionCount.get() shouldNotBeGreaterThan amountOfTasks
    }
    scheduler.stop()
  }

  test("Recurring Task") {
    val collection = ARandom.text()
    val testCouchbase = couchbase.copy(preferredCollection = collection).also {
      it.ensurePreferredCollectionExists()
    }

    val executionCount = AtomicInt(0)
    val task = Tasks.recurring("A Recurring Task-${UUID.randomUUID()}", Schedules.fixedDelay(3.seconds.toJavaDuration()), TestTaskData::class.java)
      .initialData(TestTaskData("test"))
      .execute { _, _ -> executionCount.incrementAndGet() }

    val processingScheduler = CouchbaseScheduler.create(testCouchbase, startupTasks = listOf(task), name = testCase.name.testName + "Processing")
    processingScheduler.start()

    eventually(10.seconds) {
      executionCount.get() shouldBe 2
      executionCount.get() shouldNotBeGreaterThan 2
    }

    processingScheduler.stop()
  }

  test("multiple schedulers racing") {
    val collection = ARandom.text()
    val testCouchbase = couchbase.copy(preferredCollection = collection).also {
      it.ensurePreferredCollectionExists()
    }

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("RacingTasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val count = 200
    val tasks = (1..count)
    val time = Instant.now()

    val scheduler = CouchbaseScheduler.create(testCouchbase, name = testCase.name.testName) as SchedulerClient
    tasks.map { i -> async { scheduler.scheduleIfNotExists(task.instance("racingTask-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()

    val scheduler1 = CouchbaseScheduler.create(testCouchbase, knownTasks = listOf(task), name = testCase.name.testName + "Racer 1")
    val scheduler2 = CouchbaseScheduler.create(testCouchbase, knownTasks = listOf(task), name = testCase.name.testName + "Racer 2")
    val scheduler3 = CouchbaseScheduler.create(testCouchbase, knownTasks = listOf(task), name = testCase.name.testName + "Racer 3")

    awaitAll(
      async { scheduler1.start() },
      async { scheduler2.start() },
      async { scheduler3.start() }
    )

    eventually(30.seconds) {
      executionCount.get() shouldBe count
      executionCount.get() shouldNotBeGreaterThan count
    }
  }

  test("failing one time task is retried") {
    val collection = ARandom.text()
    val testCouchbase = couchbase.copy(preferredCollection = collection).also {
      it.ensurePreferredCollectionExists()
    }

    val executionCount = AtomicInt(0)
    val maxRetry = 3
    val task = Tasks.oneTime("Failing Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .onFailure { executionComplete, executionOperations ->
        if (executionComplete.execution.consecutiveFailures < maxRetry) {
          executionOperations.reschedule(executionComplete, Instant.now().plusMillis(100))
        }
      }
      .execute { _, _ ->
        executionCount.incrementAndGet()
        error("on purpose failure")
      }

    val scheduler = CouchbaseScheduler.create(testCouchbase, knownTasks = listOf(task), name = testCase.name.testName)
      .also { it.start() }

    scheduler.schedule(task.instance("failing-task-${UUID.randomUUID()}", TestTaskData("test")), Instant.now())

    eventually(30.seconds) {
      executionCount.get() shouldBe 3
      executionCount.get() shouldNotBeGreaterThan 3
    }
  }
})
