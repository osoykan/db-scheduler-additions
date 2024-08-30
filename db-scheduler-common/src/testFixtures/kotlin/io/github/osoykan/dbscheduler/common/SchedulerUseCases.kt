package io.github.osoykan.dbscheduler.common

import arrow.atomic.AtomicInt
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.*
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.ints.shouldNotBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

typealias SchedulerFactory<T> = (db: T, tasks: List<Task<*>>, startupTasks: List<RecurringTask<*>>, name: String) -> Scheduler

data class CaseDefinition<T : DocumentDatabase<T>>(
  val db: T,
  val schedulerFactory: SchedulerFactory<T>
)

data class TestTaskData(val name: String)

abstract class SchedulerUseCases<T : DocumentDatabase<T>> : AnnotationSpec() {
  abstract suspend fun caseDefinition(): CaseDefinition<T>

  @Test
  suspend fun `should start`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db.withCollection(collection)
      .also { it.ensureCollectionExists() }

    val scheduler = definition.schedulerFactory(testContextDb, listOf(), listOf(), name)
    scheduler.start()
    scheduler.schedulerState.isStarted shouldBe true
    scheduler.stop()
  }

  @Test
  suspend fun `should schedule a task`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db.withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("A One Time Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name)
      .also { it.start() }

    scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test")), Instant.now().plusMillis(200))

    eventually(3.minutes) {
      executionCount.get() shouldBe 1
      executionCount.get() shouldNotBeGreaterThan 1
    }
    scheduler.stop()
  }

  @Test
  suspend fun `should schedule 50 tasks`() = coroutineScope {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db.withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("50Tasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val amountOfTasks = 50
    val tasks = (1..amountOfTasks)
    val time = Instant.now()
    val scheduler = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name)
      .also { it.start() }

    tasks.map { i -> async { scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()

    eventually(3.minutes) {
      executionCount.get() shouldBe amountOfTasks
      executionCount.get() shouldNotBeGreaterThan amountOfTasks
    }
    scheduler.stop()
  }

  @Test
  suspend fun `recurring task`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db.withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks.recurring(
      "A Recurring Task-${UUID.randomUUID()}",
      Schedules.fixedDelay(3.seconds.toJavaDuration()),
      TestTaskData::class.java
    ).initialData(TestTaskData("test"))
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition.schedulerFactory(testContextDb, listOf(), listOf(task), name)
      .also { it.start() }

    eventually(3.minutes) {
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
    val testContextDb = definition.db.withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("RacingTasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val count = 200
    val tasks = (1..count)
    val time = Instant.now()

    val scheduler = definition.schedulerFactory(testContextDb, listOf(), listOf(), name) as SchedulerClient
    tasks.map { i ->
      async {
        scheduler.scheduleIfNotExists(task.instance("racingTask-${UUID.randomUUID()}", TestTaskData("test-$i")), time)
      }
    }.awaitAll()

    val scheduler1 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 1")
    val scheduler2 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 2")
    val scheduler3 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 3")

    awaitAll(
      async { scheduler1.start() },
      async { scheduler2.start() },
      async { scheduler3.start() }
    )

    eventually(3.minutes) {
      executionCount.get() shouldBe count
      executionCount.get() shouldNotBeGreaterThan count
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
    val testContextDb = definition.db.withCollection(collection)
      .also { it.ensureCollectionExists() }

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

    val scheduler = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name)
      .also { it.start() }

    scheduler.schedule(task.instance("failing-task-${UUID.randomUUID()}", TestTaskData("test")), Instant.now())

    eventually(3.minutes) {
      executionCount.get() shouldBe 3
      executionCount.get() shouldNotBeGreaterThan 3
    }

    scheduler.stop()
  }
}
