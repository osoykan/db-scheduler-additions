package com.github.kagkarlsson.scheduler.couchbase

import arrow.atomic.AtomicInt
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldNotBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class TestTaskData(val name: String)

class CouchbaseSchedulerTest : FunSpec({
  test("scheduler should be started") {
    scheduler(testCase.name.testName) {
      schedulerState.isStarted shouldBe true
    }
  }

  test("should schedule a task") {
    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("A One Time Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    scheduler(testCase.name.testName, task) {
      schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test")), Instant.now().plusMillis(200))
      eventually(30.seconds) {
        executionCount.get() shouldBe 1
        executionCount.get() shouldNotBeGreaterThan 1
      }
    }
  }

  test("schedule 50 tasks") {
    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("50Tasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val tasks = (1..50)
    val time = Instant.now()
    scheduler(testCase.name.testName, task) {
      tasks.map { i -> async { schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()
      eventually(30.seconds) {
        executionCount.get() shouldBe 50
        executionCount.get() shouldNotBeGreaterThan 50
      }
    }
  }

  test("Recurring Task: Scheduling and Processing seperated") {
    val executionCount = AtomicInt(0)
    val task = Tasks.recurring("A Recurring Task-${UUID.randomUUID()}", Schedules.fixedDelay(3.seconds.toJavaDuration()), TestTaskData::class.java)
      .initialData(TestTaskData("test"))
      .execute { _, _ -> executionCount.incrementAndGet() }

    scheduler("Recurring Scheduler Scheduling") {
      val client = this as SchedulerClient
      client.schedule(task.instance("recurring-task", TestTaskData("test")), Instant.now())
    }

    scheduler("Recurring Scheduler Processing", task) {
      eventually(8.seconds) {
        executionCount.get() shouldBe 2
        executionCount.get() shouldNotBeGreaterThan 2
      }
    }
  }

  test("multiple schedulers racing") {
    val executionCount = AtomicInt(0)
    val task = Tasks.oneTime("200Tasks-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val count = 200
    val tasks = (1..count)
    val time = Instant.now()
    scheduler(testCase.name.testName) {
      tasks.map { i -> async { schedule(task.instance("racingTask-${UUID.randomUUID()}", TestTaskData("test-$i")), time) } }.awaitAll()
    }

    scheduler(testCase.name.testName + "Racer 1", task) {
      scheduler(testCase.name.testName + "Racer 2", task) {
        scheduler(testCase.name.testName + "Racer 3", task) {
          eventually(30.seconds) {
            executionCount.get() shouldBe count
            executionCount.get() shouldNotBeGreaterThan count
          }
        }
      }
    }

    println(CouchbaseScheduler.AppMicrometer.registry.scrape())
  }

  test("failing one time task is retried") {
    val maxRetry = 3
    val executionCount = AtomicInt(0)
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

    scheduler(testCase.name.testName, task) {
      schedule(task.instance("failing-task-${UUID.randomUUID()}", TestTaskData("test")), Instant.now())
      eventually(30.seconds) {
        executionCount.get() shouldBe 3
        executionCount.get() shouldNotBeGreaterThan 3
      }
    }
  }
})
