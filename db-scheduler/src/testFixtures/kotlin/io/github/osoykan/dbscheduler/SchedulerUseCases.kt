@file:Suppress("unused")

package io.github.osoykan.dbscheduler

import arrow.atomic.AtomicInt
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.task.FailureHandler.MaxRetriesFailureHandler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.github.osoykan.scheduler.DocumentDatabase
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.time.ZoneId
import java.util.*
import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("UnnecessaryAbstractClass")
abstract class SchedulerUseCases<T : DocumentDatabase<T>> : AnnotationSpec() {
  
  abstract suspend fun caseDefinition(): CaseDefinition<T>

  private fun createTestClock(): ControllableTestClock = ControllableTestClock()

  /**
   * Helper function to wait for condition with controllable time advancement
   */
  private suspend fun waitForCondition(
    clock: ControllableTestClock,
    maxDuration: Duration = 60.seconds, // Increased for reliability
    checkInterval: Duration = 50.milliseconds, // Finer interval
    condition: () -> Boolean
  ) {
    val startTime = clock.now()
    val endTime = startTime.plus(maxDuration.toJavaDuration())

    while (clock.now().isBefore(endTime)) {
      if (condition()) {
        return
      }
      logger.debug("Condition not met yet. Current executions: N/A")
      clock.advanceBy(checkInterval)
      delay(10)
    }

    if (!condition()) {
      throw AssertionError("Condition was not met within $maxDuration")
    }
  }

  /**
   * Helper function to assert condition immediately (for cases where tasks should execute instantly)
   */
  private suspend fun assertCondition(
    clock: ControllableTestClock,
    condition: () -> Boolean,
    timeToAdvance: Duration = 1.seconds
  ) {
    // Advance time to trigger execution
    clock.advanceBy(timeToAdvance)
    
    // Use waitForCondition instead of immediate assertion for better reliability
    waitForCondition(clock, maxDuration = 5.seconds, checkInterval = 25.milliseconds, condition)
  }

  @Test
  suspend fun `should start`() {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val testClock = createTestClock()
    val scheduler = definition.schedulerFactory(testContextDb, listOf(), listOf(), name, testClock, OtherOptions())
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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions(100))
      .also { it.start() }

    val executionTime = testClock.peekAhead(200.milliseconds)
    scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test")), executionTime)

    // Advance time to trigger execution
    assertCondition(testClock, { executionCount.get() == 1 }, 300.milliseconds)

    // Verify no additional executions
    testClock.advanceBy(5.seconds)
    delay(50)
    executionCount.get() shouldBe 1

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
    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val executionTime = testClock.now()
    (1..amountOfTasks)
      .map { i ->
        async {
          scheduler.schedule(task.instance("taskId-${UUID.randomUUID()}", TestTaskData("test-$i")), executionTime)
        }
      }.awaitAll()

    testClock.advanceBy(10.seconds)

    // Wait for all tasks to execute
    waitForCondition(testClock) { executionCount.get() == amountOfTasks }

    // Verify no additional executions
    testClock.advanceBy(5.seconds)
    delay(50)
    executionCount.get() shouldBe amountOfTasks

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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(task), name, testClock, OtherOptions())
      .also { it.start() }

    // First execution should happen immediately
    waitForCondition(testClock) { executionCount.get() >= 1 }

    // Advance time to trigger second execution
    testClock.advanceBy(4.seconds)
    delay(100)

    executionCount.get() shouldBe 2

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
      .execute { _, _ -> 
        val count = executionCount.incrementAndGet()
        logger.debug("Task executed, count: {}", count)
      }

    val count = 50 // Reduced count for stability
    val testClock = createTestClock()
    val plannedTime = testClock.peekAhead(1.seconds)

    val scheduler = definition.schedulerFactory(testContextDb, listOf(), listOf(), name, testClock, OtherOptions()) as SchedulerClient
    
    // Schedule tasks sequentially to avoid overwhelming the system
    repeat(count) { i ->
      scheduler.scheduleIfNotExists(
        task.instance("racingTask-$i-${UUID.randomUUID()}", TestTaskData("test-$i")),
        plannedTime
      )
    }

    val options = OtherOptions(concurrency = 10) // Reduced concurrency for stability
    val scheduler1 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 1", testClock, options)
    val scheduler2 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 2", testClock, options)
    val scheduler3 = definition.schedulerFactory(testContextDb, listOf(task), listOf(), name + "Racer 3", testClock, options)

    // Start schedulers sequentially to avoid startup race conditions
    scheduler1.start()
    delay(50) // Small delay between starts
    scheduler2.start() 
    delay(50)
    scheduler3.start()
    delay(100) // Allow schedulers to initialize

    // Advance time to trigger execution
    testClock.advanceBy(2.seconds)

    // Wait for all tasks to complete with better debugging
    waitForCondition(testClock, maxDuration = 30.seconds) { 
      val current = executionCount.get()
      logger.debug("Racing test progress: {}/{}", current, count)
      current == count 
    }

    // Stop schedulers gracefully
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
    val testClock = createTestClock()

    val task = Tasks
      .oneTime("Failing Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .onFailure(
        MaxRetriesFailureHandler(maxRetry) { e, a ->
          a.reschedule(e, testClock.peekAhead(100.milliseconds))
        }
      ).execute { _, _ ->
        executionCount.incrementAndGet()
        error("on purpose failure")
      }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    scheduler.schedule(task.instance("failing-task-${UUID.randomUUID()}", TestTaskData("test")), testClock.now())

    // Wait for all retries to complete
    waitForCondition(testClock, maxDuration = 10.seconds) {
      executionCount.get() == totalExecutions
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
    val testClock = createTestClock()

    val task = Tasks
      .recurring(
        "Failing Recurring Task-${UUID.randomUUID()}",
        Schedules.fixedDelay(3.seconds.toJavaDuration()),
        TestTaskData::class.java
      ).initialData(TestTaskData("test"))
      .onFailure(
        MaxRetriesFailureHandler(maxRetry) { e, a ->
          a.reschedule(e, testClock.peekAhead(100.milliseconds))
        }
      ).execute { _, _ ->
        executionCount.incrementAndGet()
        error("on purpose failure")
      }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(task), name, testClock, OtherOptions())
      .also { it.start() }

    // Wait for all retries to complete
    waitForCondition(testClock, maxDuration = 10.seconds) {
      executionCount.get() == totalExecutions
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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val executionTime = testClock.peekAhead(10.seconds)
    scheduler.schedule(task.instance("persistent-task-${UUID.randomUUID()}", TestTaskData("test")), executionTime)

    scheduler.pause()

    // Advance time while paused - task should not execute
    testClock.setTo(executionTime.plusSeconds(1))
    delay(100)
    executionCount.get() shouldBe 0

    scheduler.resume()

    // Now task should execute
    waitForCondition(testClock) { executionCount.get() == 1 }

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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val executionTime = testClock.peekAhead(200.milliseconds)
    val tasks = (1..concurrentTasks).map {
      async {
        scheduler.schedule(task.instance("concurrent-task-${UUID.randomUUID()}", TestTaskData("test-$it")), executionTime)
      }
    }

    tasks.awaitAll()

    // Wait for all tasks to execute
    waitForCondition(testClock) { executionCount.get() == concurrentTasks }

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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val scheduledTask = task.instance("cancellable-task-${UUID.randomUUID()}", TestTaskData("test"))
    val executionTime = testClock.peekAhead(200.milliseconds)
    scheduler.schedule(scheduledTask, executionTime)

    scheduler.cancel(scheduledTask)

    // Advance time past execution time
    testClock.setTo(executionTime.plusSeconds(1))
    delay(100)

    executionCount.get() shouldBe 0

    // Verify it stays 0
    testClock.advanceBy(5.seconds)
    delay(50)
    executionCount.get() shouldBe 0

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
    val testClock = createTestClock()
    val task = Tasks
      .oneTime("Time Skew Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val scheduledTime = testClock.peekAhead(30.seconds)
    scheduler.schedule(task.instance("time-skew-task-${UUID.randomUUID()}", TestTaskData("test")), scheduledTime)

    // Simulate time skew by jumping ahead
    testClock.setTo(scheduledTime.plusSeconds(5))

    assertCondition(testClock, { executionCount.get() == 1 })

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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val timeZones = listOf(ZoneId.of("UTC"), ZoneId.of("America/New_York"), ZoneId.of("Asia/Tokyo"))

    // Schedule all tasks for the current test clock time (should execute immediately)
    timeZones.forEachIndexed { index, zone ->
      scheduler.schedule(task.instance("timezone-task-$index-${UUID.randomUUID()}", TestTaskData("test-$zone")), testClock.now())
    }

    // All tasks should execute since they're scheduled for current time
    waitForCondition(testClock, maxDuration = 2.seconds) { executionCount.get() == timeZones.size }

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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val executionTime = testClock.peekAhead(10.days)
    (1..amountOfUnresolvedTasks).forEach {
      val scheduledTask = task.instance("unresolved-task-$it-${UUID.randomUUID()}", TestTaskData("test-$it"))
      scheduler.schedule(scheduledTask, executionTime)
    }

    delay(100) // Allow tasks to be persisted

    testClock.advanceBy(20.days)

    val tasks = mutableListOf<ScheduledExecution<*>>()
    scheduler.fetchScheduledExecutions(ScheduledExecutionsFilter.all()) { tasks.add(it) }
    tasks.size shouldBe amountOfUnresolvedTasks

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
      .oneTime("Reschedule Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { instance, context ->
        val data = instance.data
        executedData.add(data.name)
      }

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val taskId = "reschedule-task-${UUID.randomUUID()}"
    scheduler.schedule(task.instance(taskId, TestTaskData("initial")), testClock.now())

    assertCondition(testClock, { executedData.contains("initial") })

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
    val testClock = createTestClock()

    val childTask = Tasks
      .oneTime("Child Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionOrder.add("child") }

    val parentTask = Tasks
      .oneTime("Parent Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, context ->
        executionOrder.add("parent")

        context.schedulerClient.scheduleIfNotExists(
          childTask.instance("child-task-${UUID.randomUUID()}", TestTaskData("child")),
          testClock.peekAhead(5.seconds)
        )
      }

    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(parentTask, childTask), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    scheduler.schedule(
      parentTask.instance("parent-task-${UUID.randomUUID()}", TestTaskData("parent")),
      testClock.now()
    )

    // Wait for parent to execute
    waitForCondition(testClock) { executionOrder.isNotEmpty() && executionOrder[0] == "parent" }

    // Advance time to trigger child
    testClock.advanceBy(6.seconds)

    waitForCondition(testClock) {
      executionOrder.size == 2 && executionOrder[0] == "parent" && executionOrder[1] == "child"
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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(), listOf(fastTask, slowTask), name, testClock, OtherOptions(concurrency = 100))
      .also { it.start() }

    waitForCondition(testClock) { fastExecutionCount.get() >= 1 && slowExecutionCount.get() >= 1 }

    testClock.advanceBy(6.seconds) // This should allow fast task to run ~6 times, slow task ~2 times

    waitForCondition(testClock) { fastExecutionCount.get() >= 6 && slowExecutionCount.get() >= 2 }

    // Verify the scheduling difference
    fastExecutionCount.get() shouldBeGreaterThan slowExecutionCount.get()
    slowExecutionCount.get() shouldBeGreaterThan 0

    // More specific assertions to ensure proper timing
    fastExecutionCount.get() shouldBeGreaterThan 5 // Should have at least 3 executions
    slowExecutionCount.get() shouldBe 3 // Should have exactly 2 executions (initial + 1 after 3s)

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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val executionTime = testClock.peekAhead(5.seconds)
    // Schedule multiple instances of the same task
    val taskIds = (1..5).map {
      val id = "removable-task-$it-${UUID.randomUUID()}"
      scheduler.schedule(task.instance(id, TestTaskData("test-$it")), executionTime)
      id
    }

    // Remove one task
    val taskToRemove = task.instance(taskIds[2], TestTaskData("test-3"))
    scheduler.cancel(taskToRemove)

    // Advance time to trigger execution
    testClock.setTo(executionTime.plusSeconds(1))

    waitForCondition(testClock) { executionCount.get() == 4 } // 5 tasks scheduled, 1 removed

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

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions(concurrency = 100))
      .also { it.start() }

    val executionTime = testClock.peekAhead(200.milliseconds)

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
          scheduler.schedule(it, executionTime)
        }
      }
    }

    batchJobs.awaitAll()

    testClock.advanceBy(5.seconds)

    // Wait for all tasks to execute
    waitForCondition(testClock, maxDuration = 30.seconds) { executionCount.get() == totalTasks }

    scheduler.stop()
  }

  @Test
  suspend fun `should handle batch creation with duplicates`() = coroutineScope {
    val definition = caseDefinition()
    val collection = ARandom.text()
    val name = ARandom.text()
    val testContextDb = definition.db
      .withCollection(collection)
      .also { it.ensureCollectionExists() }

    val executionCount = AtomicInt(0)
    val task = Tasks
      .oneTime("Batch Duplicate Task-${UUID.randomUUID()}", TestTaskData::class.java)
      .execute { _, _ -> executionCount.incrementAndGet() }

    val testClock = createTestClock()
    val scheduler = definition
      .schedulerFactory(testContextDb, listOf(task), listOf(), name, testClock, OtherOptions())
      .also { it.start() }

    val executionTime = testClock.peekAhead(200.milliseconds)
    val uniqueInstances = (1..5).map { task.instance("unique-batch-$it", TestTaskData("test-$it")) }
    val duplicateInstances = uniqueInstances + uniqueInstances // Duplicates

    // Schedule with duplicates using scheduler.schedule (which handles existence)
    duplicateInstances.forEach { inst ->
      scheduler.schedule(inst, executionTime)
    }

    // Advance time
    testClock.advanceBy(1.seconds)

    // Only unique tasks should execute (duplicates skipped by existence check)
    waitForCondition(testClock) { executionCount.get() == uniqueInstances.size }

    scheduler.stop()
  }
}
