package io.github.osoykan.scheduler

import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter
import com.github.kagkarlsson.scheduler.task.*
import java.time.*
import java.util.*
import java.util.function.*

interface CoroutineTaskRepository {
  suspend fun createIndexes()

  suspend fun createIfNotExists(execution: SchedulableInstance<*>): Boolean

  suspend fun getDue(now: Instant, limit: Int): List<Execution>

  suspend fun replace(toBeReplaced: Execution, newInstance: SchedulableInstance<*>): Instant

  suspend fun getScheduledExecutions(filter: ScheduledExecutionsFilter, consumer: Consumer<Execution>)

  suspend fun getScheduledExecutions(filter: ScheduledExecutionsFilter, taskName: String, consumer: Consumer<Execution>)

  suspend fun lockAndFetchGeneric(now: Instant, limit: Int): List<Execution>

  suspend fun lockAndGetDue(now: Instant, limit: Int): List<Execution>

  suspend fun remove(execution: Execution)

  suspend fun reschedule(
    execution: Execution,
    nextExecutionTime: Instant,
    lastSuccess: Instant?,
    lastFailure: Instant?,
    consecutiveFailures: Int
  ): Boolean

  suspend fun reschedule(
    execution: Execution,
    nextExecutionTime: Instant,
    newData: Any,
    lastSuccess: Instant?,
    lastFailure: Instant?,
    consecutiveFailures: Int
  ): Boolean

  suspend fun pick(e: Execution, timePicked: Instant): Optional<Execution>

  suspend fun getDeadExecutions(olderThan: Instant): List<Execution>

  suspend fun updateHeartbeatWithRetry(execution: Execution, newHeartbeat: Instant, tries: Int): Boolean

  suspend fun updateHeartbeat(execution: Execution, heartbeatTime: Instant): Boolean

  suspend fun getExecutionsFailingLongerThan(interval: Duration): List<Execution>

  suspend fun getExecution(taskName: String, taskInstanceId: String): Optional<Execution>

  suspend fun getExecution(taskInstance: TaskInstanceId): Optional<Execution> = getExecution(taskInstance.taskName, taskInstance.id)

  suspend fun removeExecutions(taskName: String): Int

  suspend fun verifySupportsLockAndFetch()

  fun <T> memoize(original: Supplier<T>): Supplier<T> {
    return object : Supplier<T> {
      private var delegate: Supplier<T> = Supplier { firstTime() }

      @Volatile
      private var initialized = false

      override fun get(): T {
        return delegate.get()
      }

      @Synchronized
      private fun firstTime(): T {
        if (!initialized) {
          val value = original.get()
          delegate = Supplier { value }
          initialized = true
        }
        return delegate.get()
      }
    }
  }
}
