package io.github.osoykan.dbscheduler

import arrow.core.*
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.task.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.*
import java.util.*
import java.util.function.Consumer

class KTaskRepository(
  private val taskRepository: CoroutineTaskRepository,
  private val scope: CoroutineScope
) : TaskRepository {
  private val logger = LoggerFactory.getLogger(KTaskRepository::class.java)

  fun createIndexes() = runBlocking(scope.coroutineContext) {
    logger.debug("Creating indexes for DbScheduler")
    Either.catch { taskRepository.createIndexes() }
      .onRight { logger.debug("Created indexes for DbScheduler") }
      .mapLeft { logger.error("Failed to create indexes for db-scheduler", it) }
  }

  override fun createIfNotExists(execution: SchedulableInstance<*>): Boolean = runBlocking(scope.coroutineContext) {
    logger.debug("Creating if not exists for {}", execution)
    Either.catch { taskRepository.createIfNotExists(execution) }
      .onRight { logger.debug("Created if not exists for {}", execution) }
      .mapLeft {
        logger.error("Failed to createIfNotExists for $execution", it)
        throw it
      }.merge()
  }

  override fun getDue(now: Instant, limit: Int): List<Execution> = runBlocking(scope.coroutineContext) {
    logger.debug("Getting due for {}, {}", now, limit)
    Either.catch { taskRepository.getDue(now, limit) }
      .onRight { logger.debug("Got due for {}, {}", now, limit) }
      .mapLeft {
        logger.error("Failed to getDue for $now, $limit", it)
        throw it
      }.merge()
  }

  override fun replace(toBeReplaced: Execution, newInstance: SchedulableInstance<*>): Instant = runBlocking(scope.coroutineContext) {
    logger.debug("Replacing for {}, {}", toBeReplaced, newInstance)
    Either.catch {
      taskRepository.replace(toBeReplaced, newInstance)
    }.onRight { logger.debug("Replaced for {}, {}", toBeReplaced, newInstance) }.mapLeft {
      logger.error("Failed to replace for $toBeReplaced, $newInstance", it)
      throw it
    }.merge()
  }

  override fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    consumer: Consumer<Execution>
  ) = runBlocking(scope.coroutineContext) {
    logger.debug("Getting scheduled executions for {}", filter)
    Either.catch { taskRepository.getScheduledExecutions(filter, consumer) }
      .onRight { logger.debug("Got scheduled executions for {}", filter) }
      .mapLeft {
        logger.error("Failed to getScheduledExecutions for $filter", it)
        throw it
      }.merge()
  }

  override fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    taskName: String,
    consumer: Consumer<Execution>
  ) = runBlocking(scope.coroutineContext) {
    logger.debug("Getting scheduled executions for {}, {}", filter, taskName)
    Either.catch { taskRepository.getScheduledExecutions(filter, taskName, consumer) }
      .onRight { logger.debug("Got scheduled executions for {}, {}", filter, taskName) }
      .mapLeft {
        logger.error("Failed to getScheduledExecutions for $filter, $taskName", it)
        throw it
      }.merge()
  }

  override fun lockAndFetchGeneric(now: Instant, limit: Int): List<Execution> = runBlocking(scope.coroutineContext) {
    logger.debug("Locking and fetching generic for {}, {}", now, limit)
    Either.catch { taskRepository.lockAndFetchGeneric(now, limit) }
      .onRight { logger.debug("Locked and fetched generic for {}, {}", now, limit) }
      .mapLeft {
        logger.error("Failed to lockAndFetchGeneric for $now, $limit", it)
        throw it
      }.merge()
  }

  override fun lockAndGetDue(now: Instant, limit: Int): List<Execution> = runBlocking(scope.coroutineContext) {
    logger.debug("Locking and getting due for {}, {}", now, limit)
    Either.catch { taskRepository.lockAndGetDue(now, limit) }
      .onRight { logger.debug("Locked and got due for {}, {}", now, limit) }
      .mapLeft {
        logger.error("Failed to lockAndGetDue for $now, $limit", it)
        throw it
      }.merge()
  }

  override fun remove(execution: Execution) = runBlocking(scope.coroutineContext) {
    logger.debug("Removing for {}", execution)
    Either.catch { taskRepository.remove(execution) }
      .onRight { logger.debug("Removed for {}", execution) }
      .mapLeft {
        logger.error("Failed to remove for $execution", it)
        throw it
      }.merge()
  }

  override fun reschedule(
    execution: Execution,
    nextExecutionTime: Instant,
    lastSuccess: Instant?,
    lastFailure: Instant?,
    consecutiveFailures: Int
  ): Boolean = runBlocking(scope.coroutineContext) {
    logger.debug("Rescheduling for {}, {}, {}, {}, {}", execution, nextExecutionTime, lastSuccess, lastFailure, consecutiveFailures)
    Either.catch { taskRepository.reschedule(execution, nextExecutionTime, lastSuccess, lastFailure, consecutiveFailures) }
      .onRight {
        logger.debug(
          "Rescheduled for {}, {}, {}, {}, {}",
          execution,
          nextExecutionTime,
          lastSuccess,
          lastFailure,
          consecutiveFailures
        )
      }
      .mapLeft {
        logger.error("Failed to reschedule for $execution, $nextExecutionTime, $lastSuccess, $lastFailure, $consecutiveFailures", it)
        throw it
      }.merge()
  }

  override fun reschedule(
    execution: Execution,
    nextExecutionTime: Instant,
    newData: Any,
    lastSuccess: Instant?,
    lastFailure: Instant?,
    consecutiveFailures: Int
  ): Boolean = runBlocking(scope.coroutineContext) {
    logger.debug(
      "Rescheduling for {}, {}, {}, {}, {}, {}",
      execution,
      nextExecutionTime,
      newData,
      lastSuccess,
      lastFailure,
      consecutiveFailures
    )
    Either.catch { taskRepository.reschedule(execution, nextExecutionTime, newData, lastSuccess, lastFailure, consecutiveFailures) }
      .onRight {
        logger.debug(
          "Rescheduled for {}, {}, {}, {}, {}, {}",
          execution,
          nextExecutionTime,
          newData,
          lastSuccess,
          lastFailure,
          consecutiveFailures
        )
      }
      .mapLeft {
        logger.error(
          "Failed to reschedule for $execution, $nextExecutionTime, $newData, $lastSuccess, $lastFailure, $consecutiveFailures",
          it
        )
        throw it
      }.merge()
  }

  override fun pick(e: Execution, timePicked: Instant): Optional<Execution> = runBlocking(scope.coroutineContext) {
    logger.debug("Picking for {}, {}", e, timePicked)
    Either.catch { taskRepository.pick(e, timePicked) }
      .onRight { logger.debug("Picked for execution {}, lastHeartbeat:{}", e, timePicked) }
      .mapLeft {
        logger.error("Failed to pick for $e, $timePicked", it)
        throw it
      }.merge()
  }

  override fun getDeadExecutions(olderThan: Instant): List<Execution> = runBlocking(scope.coroutineContext) {
    logger.debug("Getting dead executions for {}", olderThan)
    Either.catch { taskRepository.getDeadExecutions(olderThan) }
      .onRight { logger.debug("Got dead executions for {}", olderThan) }
      .mapLeft {
        logger.error("Failed to getDeadExecutions for $olderThan", it)
        throw it
      }.merge()
  }

  override fun updateHeartbeatWithRetry(
    execution: Execution,
    newHeartbeat: Instant,
    tries: Int
  ): Boolean = runBlocking(scope.coroutineContext) {
    logger.debug("Updating heartbeat with retry for {}, {}, {}", execution, newHeartbeat, tries)
    Either.catch { taskRepository.updateHeartbeatWithRetry(execution, newHeartbeat, tries) }
      .onRight { logger.debug("Updated heartbeat with retry for {}, {}, {}", execution, newHeartbeat, tries) }
      .mapLeft {
        logger.error("Failed to updateHeartbeatWithRetry for $execution, $newHeartbeat, $tries", it)
        throw it
      }.merge()
  }

  override fun updateHeartbeat(execution: Execution, heartbeatTime: Instant): Boolean = runBlocking(scope.coroutineContext) {
    logger.debug("Updating heartbeat for {}, {}", execution, heartbeatTime)
    Either.catch { taskRepository.updateHeartbeat(execution, heartbeatTime) }
      .onRight { logger.debug("Updated heartbeat for {}, {}", execution, heartbeatTime) }
      .mapLeft {
        logger.error("Failed to updateHeartbeat for $execution, $heartbeatTime", it)
        throw it
      }.merge()
  }

  override fun getExecutionsFailingLongerThan(interval: Duration): List<Execution> = runBlocking(scope.coroutineContext) {
    logger.debug("Getting executions failing longer than {}", interval)
    Either.catch { taskRepository.getExecutionsFailingLongerThan(interval) }
      .onRight { logger.debug("Got executions failing longer than {}", interval) }
      .mapLeft {
        logger.error("Failed to getExecutionsFailingLongerThan for $interval", it)
        throw it
      }.merge()
  }

  override fun getExecution(taskName: String, taskInstanceId: String): Optional<Execution> = runBlocking(scope.coroutineContext) {
    logger.debug("Getting execution for $taskName, $taskInstanceId")
    Either.catch { taskRepository.getExecution(taskName, taskInstanceId) }
      .onRight { logger.debug("Got execution for $taskName, $taskInstanceId") }
      .mapLeft {
        logger.error("Failed to getExecution for $taskName, $taskInstanceId", it)
        throw it
      }.merge()
  }

  override fun removeExecutions(taskName: String): Int = runBlocking(scope.coroutineContext) {
    logger.debug("Removing executions for $taskName")
    Either.catch { taskRepository.removeExecutions(taskName) }
      .mapLeft {
        logger.error("Failed to removeExecutions for $taskName", it)
        throw it
      }.merge()
  }

  override fun verifySupportsLockAndFetch() = runBlocking(scope.coroutineContext) {
    logger.debug("Verifying supports lock and fetch")
    Either.catch { taskRepository.verifySupportsLockAndFetch() }
      .onRight { logger.debug("Verified supports lock and fetch") }
      .mapLeft {
        logger.error("Failed to verifySupportsLockAndFetch", it)
        throw it
      }.merge()
  }
}
