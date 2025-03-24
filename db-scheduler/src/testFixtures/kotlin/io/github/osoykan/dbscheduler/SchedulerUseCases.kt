@file:Suppress("unused")

package io.github.osoykan.dbscheduler

import arrow.atomic.AtomicInt
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Clock
import com.github.kagkarlsson.scheduler.task.FailureHandler.MaxRetriesFailureHandler
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.*
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.github.osoykan.scheduler.DocumentDatabase
import io.kotest.assertions.nondeterministic.*
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.ints.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.time.*
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class OtherOptions(
  val concurrency: Int = 10
)

typealias SchedulerFactory<T> = (
  db: T,
  tasks: List<Task<*>>,
  startupTasks: List<RecurringTask<*>>,
  name: String,
  clock: Clock,
  options: OtherOptions
) -> Scheduler

data class CaseDefinition<T : DocumentDatabase<T>>(
  val db: T,
  val schedulerFactory: SchedulerFactory<T>
)

data class TestTaskData(
  val name: String
)

class SettableClock(
  private var instant: Instant
) : Clock {
  override fun now(): Instant = instant

  fun set(instant: Instant) {
    this.instant = instant
  }
}

abstract class SchedulerUseCases<T : DocumentDatabase<T>> : AnnotationSpec() {
  abstract suspend fun caseDefinition(): CaseDefinition<T>

  private val systemClock = SystemClock()

  @Test
  suspend fun `should start`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val scheduler = definition.schedulerFactory(testContextDb, listOf(), listOf(), name, systemClock, OtherOptions())
    scheduler.start()
    scheduler.schedulerState.isStarted shouldBe true
    scheduler.stop()
  }

  @Test
  suspend fun `should schedule a task`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("A One Time Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test")), Instant.now().plusMillis(200))

    eventually(1.minutes) {
      executionCount.get() shouldBe 1
      executionCount.get() shouldNotBeGreaterThan 1
    }

    continually(10.seconds) {
      executionCount.get() shouldBe 1
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should schedule 50 tasks`() = coroutineScope {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("50Tasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val amountOfTasks = 50
    val tasks = (1..amountOfTasks)
    val time = Instant.now()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    tasks.map { i -> async { scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()

    eventually(1.minutes) {
      executionCount.get() shouldBe amountOfTasks
      executionCount.get() shouldNotBeGreaterThan amountOfTasks
    }

    continually(10.seconds) {
      executionCount.get() shouldBe amountOfTasks
    }

    scheduler.stop()
  }

  @Test
  suspend fun `recurring task`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .recurring(
        "A Recurring Task-${UUID.randomUUID()}",
        Schedules.fixedDelay(3.seconds.toJavaDuration()),
        TestTaskData::class.java
      ).initialData(TestTaskData("test"))
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(task), name, systemClock, OtherOptions())
      .also { it.start() }

    eventually(1.minutes) {
      executionCount.get() shouldBe 2
      executionCount.get() shouldNotBeGreaterThan 2
    }

    scheduler.stop()
  }

  @Test
  suspend fun `multiple schedulers racing`() = coroutineScope {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("RacingTasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val count = 200
    val tasks = (1..count)
    val settableClock = SettableClock(Instant.now())
    val scheduler = definition.schedulerFactory(testContextDb, listOf(), listOf(), name, settableClock, OtherOptions()) as SchedulerClient
    tasks
      .map { i ->
        async {
          scheduler.scheduleIfNotExists(
            task.instance("racingTask-${UUID.randomUUID()}", TestTaskData("test-$i")),
            settableClock.now().plusSeconds(1)
          )
        }
      }.awaitAll()

    val options = OtherOptions(concurrency = 150)
    val scheduler1 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 1", settableClock, options)
    val scheduler2 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 2", settableClock, options)
    val scheduler3 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 3", settableClock, options)

    awaitAll(
      async { scheduler1.start() },
      async { scheduler2.start() },
      async { scheduler3.start() }
    )

    settableClock.set(settableClock.now().plusSeconds(15))

    eventually(30.seconds) {
      executionCount.get() shouldBe count
      executionCount.get() shouldNotBeGreaterThan count
    }

    continually(10.seconds) {
      executionCount.get() shouldBe count
    }

    scheduler1.stop()
    scheduler2.stop()
    scheduler3.stop()
  }

  @Test
  suspend fun `failing one time task retried`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val maxRetry = 3
    val totalExecutions = maxRetry + 1
    val task = Tasks
      .oneTime("Failing Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .onFailure(
        MaxRetriesFailureHandler(maxRetry) { e, a ->
          a.reschedule(e, Instant.now().plusMillis(100))
        }
      ).execute { _, _ ->
        executionCount.incrementAndGet()
        error("on purpose failure")
      }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    scheduler.schedule(task.instance("failing-task-${UUID.randomUUID()}", TestTaskData("test")), Instant.now())

    eventually(1.minutes) {
      executionCount.get() shouldBe totalExecutions
      executionCount.get() shouldNotBeGreaterThan totalExecutions
    }

    continually(10.seconds) {
      executionCount.get() shouldBe totalExecutions
    }

    scheduler.stop()
  }

  @Test
  suspend fun `when recurring task fails it should be retried`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val maxRetry = 3
    val totalExecutions = maxRetry + 1
    val task = Tasks
      .recurring(
        "Failing Recurring Task-${UUID.randomUUID()}",
        Schedules.fixedDelay(3.seconds.toJavaDuration()),
        TestTaskData::class.java
      ).initialData(TestTaskData("test"))
      .onFailure(
        MaxRetriesFailureHandler(maxRetry) { e, a ->
          a.reschedule(e, Instant.now().plusMillis(100))
        }
      ).execute { _, _ ->
        executionCount.incrementAndGet()
        error("on purpose failure")
      }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(task), name, systemClock, OtherOptions())
      .also { it.start() }

    eventually(1.minutes) {
      executionCount.get() shouldBe totalExecutions
      executionCount.get() shouldNotBeGreaterThan totalExecutions
    }

    continually(10.seconds) {
      executionCount.get() shouldBe totalExecutions
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should persist and execute tasks after pause and resume`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("Persistent Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    scheduler.schedule(task.instance("persistent-task-${UUID.randomUUID()}", TestTaskData("test")), Instant.now().plusSeconds(10))

    scheduler.pause()

    delay(2.seconds) // Give some time to ensure the task is not executed

    scheduler.resume() // Restart the scheduler

    eventually(1.minutes) {
      executionCount.get() shouldBe 1
      executionCount.get() shouldNotBeGreaterThan 1
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should execute multiple tasks concurrently`() = coroutineScope {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val concurrentTasks = 10
    val task = Tasks
      .oneTime("Concurrent Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    val tasks = (1..concurrentTasks).map {
      async {
        scheduler.schedule(task.instance("concurrent-task-${UUID.randomUUID()}", TestTaskData("test-$it")), Instant.now().plusMillis(200))
      }
    }

    tasks.awaitAll()

    eventually(1.minutes) {
      executionCount.get() shouldBe concurrentTasks
      executionCount.get() shouldNotBeGreaterThan concurrentTasks
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should cancel a task`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("Cancellable Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    val scheduledTask = task.instance("cancellable-task-${UUID.randomUUID()}", TestTaskData("test"))
    scheduler.schedule(scheduledTask, Instant.now().plusMillis(200))

    scheduler.cancel(scheduledTask)

    // Give some time to ensure the task is not executed
    delay(1.seconds)

    executionCount.get() shouldBe 0

    continually(10.seconds) {
      executionCount.get() shouldBe 0
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should handle time skew correctly`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val testClock = SettableClock(Instant.now())
    val task = Tasks
      .oneTime("Time Skew Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    scheduler.schedule(task.instance("time-skew-task-${UUID.randomUUID()}", TestTaskData("test")), Instant.now().plusSeconds(30))

    // Simulate time skew by advancing the clock
    testClock.set(Instant.now().plusSeconds(35))

    eventually(1.minutes) {
      executionCount.get() shouldBe 1
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should execute tasks correctly in different time zones`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("TimeZone Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    val timeZones = listOf(ZoneId.of("UTC"), ZoneId.of("America/New_York"), ZoneId.of("Asia/Tokyo"))
    timeZones.map { zone ->
      scheduler.schedule(task.instance("timezone-task-${UUID.randomUUID()}", TestTaskData("test")), ZonedDateTime.now(zone).toInstant())
    }

    eventually(1.minutes) {
      executionCount.get() shouldBe timeZones.size
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should return unresolved tasks`() = coroutineScope {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val amountOfUnresolvedTasks = 10
    val task = Tasks
      .oneTime("Unresolved Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    val executionTime = Instant.now().plusSeconds(10.days.inWholeSeconds)
    (1..amountOfUnresolvedTasks).onEach {
      val scheduledTask = task.instance("unresolved-task-$it-${UUID.randomUUID()}", TestTaskData("test-$it"))
      scheduler.schedule(scheduledTask, executionTime)
    }

    delay(10.seconds)
    eventually(1.minutes) {
      val tasks = mutableListOf<ScheduledExecution<*>>()
      scheduler.fetchScheduledExecutions(ScheduledExecutionsFilter.all()) {
        tasks.add(it)
      }
      tasks.size shouldBe amountOfUnresolvedTasks
    }

    continually(10.seconds) {
      val tasks = mutableListOf<ScheduledExecution<*>>()
      scheduler.fetchScheduledExecutions(ScheduledExecutionsFilter.all()) {
        tasks.add(it)
      }
      tasks.size shouldBe amountOfUnresolvedTasks
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should read data inside the task`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executedData = mutableListOf<String>()
    val task = Tasks
      .oneTime<TestTaskData>("Reschedule Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { instance, context ->
        val data = instance.data
        executedData.add(data.name)
      }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    val taskId = "reschedule-task-${UUID.randomUUID()}"
    scheduler.schedule(task.instance(taskId, TestTaskData("initial")), systemClock.now())

    eventually(5.seconds) {
      executedData.contains("initial") shouldBe true
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should handle task dependencies`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionOrder = mutableListOf<String>()
    val childTask = Tasks
      .oneTime("Child Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionOrder.add("child") }

    val parentTask = Tasks
      .oneTime("Parent Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, context ->
        executionOrder.add("parent")

        context.schedulerClient.scheduleIfNotExists(
          childTask.instance("child-task-${UUID.randomUUID()}", TestTaskData("child")),
          Instant.now().plusSeconds(5)
        )
      }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(parentTask, childTask), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    scheduler.schedule(
      parentTask.instance("parent-task-${UUID.randomUUID()}", TestTaskData("parent")),
      Instant.now()
    )

    eventually(1.minutes) {
      executionOrder.size shouldBe 2
      executionOrder[0] shouldBe "parent"
      executionOrder[1] shouldBe "child"
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should handle multiple recurring tasks with different schedules`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val fastExecutionCount = AtomicInt(0)
    val slowExecutionCount = AtomicInt(0)

    val fastTask = Tasks
      .recurring(
        "Fast Recurring Task-${UUID.randomUUID()}",
        Schedules.fixedDelay(1.seconds.toJavaDuration()),
        TestTaskData::class.java
      ).initialData(TestTaskData("fast"))
      .execute { _, _ -> fastExecutionCount.incrementAndGet() }

    val slowTask = Tasks
      .recurring(
        "Slow Recurring Task-${UUID.randomUUID()}",
        Schedules.fixedDelay(3.seconds.toJavaDuration()),
        TestTaskData::class.java
      ).initialData(TestTaskData("slow"))
      .execute { _, _ -> slowExecutionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(fastTask, slowTask), name, systemClock, OtherOptions())
      .also { it.start() }

    eventually(4.seconds) {
      fastExecutionCount.get() shouldNotBeGreaterThan 4
      slowExecutionCount.get() shouldBeGreaterThan 0
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should properly handle task removal`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("Removable Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    // Schedule multiple instances of the same task
    val taskIds = (1..5).map {
      val id = "removable-task-$it-${UUID.randomUUID()}"
      scheduler.schedule(task.instance(id, TestTaskData("test-$it")), Instant.now().plusSeconds(5))
      id
    }

    // Remove one task
    val taskToRemove = task.instance(taskIds[2], TestTaskData("test-3"))
    scheduler.cancel(taskToRemove)

    // Advance time to trigger execution
    SettableClock(Instant.now().plusSeconds(10))

    eventually(1.minutes) {
      executionCount.get() shouldBe 4 // 5 tasks scheduled, 1 removed
    }

    scheduler.stop()
  }

  @Test
  suspend fun `should handle high concurrency with task batch creation`() = coroutineScope {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val batchSize = 50
    val batches = 5
    val totalTasks = batchSize * batches
    val executionCount = AtomicInt(0)

    val task = Tasks
      .oneTime("BatchTask-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, systemClock, OtherOptions())
      .also { it.start() }

    // Create multiple batches of tasks
    val batchJobs = (1..batches).map { batchNum ->
      async {
        val batch = (1..batchSize).map { i ->
          task.instance(
            "batch-$batchNum-task-$i-${UUID.randomUUID()}",
            TestTaskData("test-$batchNum-$i")
          )
        }

        // Schedule all tasks in the batch
        batch.forEach {
          scheduler.schedule(it, Instant.now().plusMillis(200))
        }
      }
    }

    batchJobs.awaitAll()

    eventually(2.minutes) {
      executionCount.get() shouldBe totalTasks
    }

    scheduler.stop()
  }
}
