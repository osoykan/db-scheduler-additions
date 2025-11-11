package io.github.osoykan.scheduler.mongo

import arrow.core.*
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Clock
import com.github.kagkarlsson.scheduler.TaskResolver.UnresolvedTask
import com.github.kagkarlsson.scheduler.exceptions.*
import com.github.kagkarlsson.scheduler.serializer.Serializer
import com.github.kagkarlsson.scheduler.task.*
import com.mongodb.*
import com.mongodb.client.model.*
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.github.osoykan.scheduler.*
import kotlinx.coroutines.flow.*
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory
import java.time.*
import java.util.*
import java.util.function.Consumer

class MongoTaskRepository(
  private val clock: Clock,
  private val mongo: Mongo,
  private val taskResolver: TaskResolver,
  private val schedulerName: SchedulerName,
  private val serializer: Serializer
) : CoroutineTaskRepository {
  private val logger = LoggerFactory.getLogger(MongoTaskRepository::class.java)
  private val collection: MongoCollection<MongoTaskEntity> by lazy { mongo.schedulerCollection }

  override suspend fun createIfNotExists(
    execution: ScheduledTaskInstance
  ): Boolean = try {
    val entity: MongoTaskEntity = toEntity(Execution(execution.executionTime, execution.taskInstance)).copy(picked = false)
    collection.insertOne(entity).wasAcknowledged()
  } catch (e: MongoWriteException) {
    if (ErrorCategory.fromErrorCode(e.error.code) != ErrorCategory.DUPLICATE_KEY) {
      throw e
    }
    logger.debug("Task with id {} already exists in the repository", execution.documentId(), e)
    false
  }

  override suspend fun createBatch(instances: List<ScheduledTaskInstance>) {
    if (instances.isEmpty()) return

    val entitiesToInsert = instances.map { instance ->
      toEntity(Execution(instance.executionTime, instance.taskInstance)).copy(picked = false)
    }

    try {
      // Use ordered=false to continue on duplicate key errors
      collection.insertMany(entitiesToInsert, InsertManyOptions().ordered(false))
      logger.debug("Inserted {} tasks in batch", entitiesToInsert.size)
    } catch (e: Exception) {
      // Handle duplicate key errors gracefully - some tasks might already exist
      logger.debug("Batch insert completed with some duplicates ignored: {}", e.message)
    }
  }

  override suspend fun getDue(now: Instant, limit: Int): List<Execution> = collection
    .find(
      Filters.and(
        Filters.eq(MongoTaskEntity::picked.name, false),
        Filters.lte(MongoTaskEntity::executionTime.name, now)
      )
    ).limit(limit)
    .map { toExecution(it) }
    .toList()

  override suspend fun replace(
    toBeReplaced: Execution,
    newInstance: ScheduledTaskInstance
  ): Instant {
    val newExecutionTime = newInstance.executionTime
    val newExecution = Execution(newExecutionTime, newInstance.taskInstance)
    val replaced = collection.findOneAndUpdate(
      Filters.and(
        Filters.eq(MongoTaskEntity::identity.name, toBeReplaced.documentId()),
        Filters.eq(MongoTaskEntity::version.name, toBeReplaced.version)
      ),
      Updates.combine(
        Updates.set(MongoTaskEntity::taskName.name, newExecution.taskName),
        Updates.set(MongoTaskEntity::taskInstance.name, newExecution.taskInstance.id),
        Updates.set(MongoTaskEntity::picked.name, false),
        Updates.set(MongoTaskEntity::pickedBy.name, null),
        Updates.set(MongoTaskEntity::lastHeartbeat.name, null),
        Updates.set(MongoTaskEntity::lastSuccess.name, null),
        Updates.set(MongoTaskEntity::lastFailure.name, null),
        Updates.set(MongoTaskEntity::consecutiveFailures.name, 0),
        Updates.set(MongoTaskEntity::executionTime.name, newExecutionTime),
        Updates.set(MongoTaskEntity::taskData.name, serializer.serialize(newExecution.taskInstance.data)),
        Updates.inc(MongoTaskEntity::version.name, 1)
      ),
      FindOneAndUpdateOptions().upsert(false).returnDocument(ReturnDocument.AFTER)
    )
    if (replaced != null) {
      logger.debug("Task with id {} replaced with due: {}", toBeReplaced.documentId(), newExecutionTime)
      return newExecutionTime
    } else {
      throw TaskInstanceException(
        "Task with id ${toBeReplaced.documentId()} not found in the repository",
        toBeReplaced.taskName,
        toBeReplaced.taskInstance.id
      )
    }
  }

  override suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    consumer: Consumer<Execution>
  ) {
    collection
      .find(filter.asFilterBson())
      .sort(Sorts.ascending(TaskEntity::executionTime.name))
      .map { toExecution(it) }
      .collect { consumer.accept(it) }
  }

  override suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    taskName: String,
    consumer: Consumer<Execution>
  ) {
    collection
      .find(Filters.and(Filters.eq(MongoTaskEntity::taskName.name, taskName), filter.asFilterBson()))
      .sort(Sorts.ascending(MongoTaskEntity::executionTime.name))
      .map { toExecution(it) }
      .collect { consumer.accept(it) }
  }

  override suspend fun lockAndFetchGeneric(
    now: Instant,
    limit: Int
  ): List<Execution> {
    if (limit <= 0) return emptyList()

    val unresolvedCondition = UnresolvedFilter(taskResolver.unresolved)
    val pickedBy = schedulerName.name.take(SCHEDULER_NAME_TAKE)
    val lastHeartbeat = clock.now()
    val pickedExecutions = mutableListOf<Execution>()

    // Use findOneAndUpdate in a loop for atomic pick operations
    // Add a maximum iteration limit to prevent infinite loops
    var remaining = limit
    var attempts = 0
    val maxAttempts = limit * 2 // Allow up to 2x limit attempts to account for race conditions

    while (remaining > 0 && attempts < maxAttempts) {
      attempts++

      val filter = Filters.and(
        Filters.eq(MongoTaskEntity::picked.name, false),
        Filters.lte(MongoTaskEntity::executionTime.name, now),
        unresolvedCondition.asFilter()
      )

      val updatedEntity = collection.findOneAndUpdate(
        filter,
        Updates.combine(
          Updates.set(MongoTaskEntity::picked.name, true),
          Updates.set(MongoTaskEntity::pickedBy.name, pickedBy),
          Updates.set(MongoTaskEntity::lastHeartbeat.name, lastHeartbeat),
          Updates.inc(MongoTaskEntity::version.name, 1)
        ),
        FindOneAndUpdateOptions()
          .sort(Sorts.ascending(MongoTaskEntity::executionTime.name))
          .returnDocument(ReturnDocument.AFTER)
      )

      if (updatedEntity != null) {
        pickedExecutions.add(toExecution(updatedEntity))
        remaining--
      } else {
        // No more tasks available - exit early
        break
      }
    }

    logger.debug(
      "lockAndFetchGeneric: picked {} tasks out of {} requested in {} attempts",
      pickedExecutions.size,
      limit,
      attempts
    )

    return pickedExecutions
  }

  override suspend fun lockAndGetDue(
    now: Instant,
    limit: Int
  ): List<Execution> = lockAndFetchGeneric(now, limit)

  override suspend fun remove(execution: Execution) {
    collection.deleteOne(Filters.eq(TaskEntity::identity.name, execution.documentId()))
  }

  override suspend fun reschedule(
    execution: Execution,
    nextExecutionTime: Instant,
    lastSuccess: Instant?,
    lastFailure: Instant?,
    consecutiveFailures: Int
  ): Boolean = rescheduleInternal(
    execution,
    nextExecutionTime,
    None,
    lastSuccess,
    lastFailure,
    consecutiveFailures
  )

  override suspend fun reschedule(
    execution: Execution,
    nextExecutionTime: Instant,
    newData: Any,
    lastSuccess: Instant?,
    lastFailure: Instant?,
    consecutiveFailures: Int
  ): Boolean = rescheduleInternal(execution, nextExecutionTime, newData.some(), lastSuccess, lastFailure, consecutiveFailures)

  private suspend fun rescheduleInternal(
    execution: Execution,
    nextExecutionTime: Instant,
    data: Option<Any>,
    lastSuccess: Instant?,
    lastFailure: Instant?,
    consecutiveFailures: Int
  ): Boolean {
    val filter = Filters.and(
      Filters.eq(MongoTaskEntity::identity.name, execution.documentId()),
      Filters.eq(MongoTaskEntity::version.name, execution.version)
    )

    val updates = mutableListOf(
      Updates.set(MongoTaskEntity::picked.name, false),
      Updates.set(MongoTaskEntity::pickedBy.name, null),
      Updates.set(MongoTaskEntity::lastHeartbeat.name, null),
      Updates.set(MongoTaskEntity::lastSuccess.name, lastSuccess),
      Updates.set(MongoTaskEntity::lastFailure.name, lastFailure),
      Updates.set(MongoTaskEntity::consecutiveFailures.name, consecutiveFailures),
      Updates.set(MongoTaskEntity::executionTime.name, nextExecutionTime),
      Updates.inc(MongoTaskEntity::version.name, 1)
    )
    data.map { updates.add(Updates.set(MongoTaskEntity::taskData.name, serializer.serialize(it))) }

    val updated = collection.updateOne(filter, Updates.combine(updates)).modifiedCount

    if (updated != 1L) {
      throw ExecutionException("Expected one execution to be updated, but updated $updated. Indicates a bug.", execution)
    }

    return true
  }

  override suspend fun pick(
    e: Execution,
    timePicked: Instant
  ): Optional<Execution> = collection
    .updateOne(
      Filters.and(
        Filters.eq(MongoTaskEntity::identity.name, e.documentId()),
        Filters.eq(MongoTaskEntity::version.name, e.version),
        Filters.eq(MongoTaskEntity::picked.name, false)
      ),
      Updates.combine(
        Updates.set(MongoTaskEntity::picked.name, true),
        Updates.set(MongoTaskEntity::pickedBy.name, schedulerName.name.take(SCHEDULER_NAME_TAKE)),
        Updates.set(MongoTaskEntity::lastHeartbeat.name, timePicked),
        Updates.inc(MongoTaskEntity::version.name, 1)
      )
    ).let { updated ->
      if (updated.modifiedCount == 1L) {
        val maybe = getExecution(e.taskInstance)
        if (!maybe.isPresent) {
          error("Unable to find picked execution. Must have been deleted by another thread. Indicates a bug.")
        } else {
          if (!maybe.get().isPicked) {
            error("Execution was not picked after pick operation. Indicates a bug.")
          }
          maybe
        }
      } else {
        logger.debug("Execution with id {} was already picked", e.documentId())
        Optional.empty()
      }
    }

  override suspend fun getDeadExecutions(
    olderThan: Instant
  ): List<Execution> = collection
    .find(
      Filters.and(
        Filters.eq(MongoTaskEntity::picked.name, true),
        Filters.lt(MongoTaskEntity::lastHeartbeat.name, olderThan)
      )
    ).sort(Sorts.ascending(MongoTaskEntity::lastHeartbeat.name))
    .map { toExecution(it) }
    .toList()

  override suspend fun updateHeartbeatWithRetry(
    execution: Execution,
    newHeartbeat: Instant,
    tries: Int
  ): Boolean {
    var retryCount = tries
    while (retryCount > 0) {
      if (updateHeartbeat(execution, newHeartbeat)) {
        return true
      } else {
        retryCount--
      }
    }
    return false
  }

  override suspend fun getExecution(taskName: String, taskInstanceId: String): Optional<Execution> =
    getOption(TaskEntity.documentId(taskName, taskInstanceId))
      .map { toExecution(it) }
      .asJava()

  override suspend fun updateHeartbeat(
    execution: Execution,
    heartbeatTime: Instant
  ): Boolean {
    collection
      .updateOne(
        Filters.and(
          Filters.eq(MongoTaskEntity::identity.name, execution.documentId()),
          Filters.eq(MongoTaskEntity::version.name, execution.version)
        ),
        Updates.combine(
          Updates.set(MongoTaskEntity::lastHeartbeat.name, heartbeatTime)
        )
      ).let { updated ->
        if (updated.modifiedCount >= 1L) {
          logger.debug("Heartbeat updated for execution with id {}", execution.documentId())
          return true
        } else {
          logger.debug("Heartbeat update failed for execution with id {}", execution.documentId())
          return false
        }
      }
  }

  override suspend fun getExecutionsFailingLongerThan(interval: Duration): List<Execution> {
    val boundary = clock.now().minus(interval)
    return collection
      .find(
        Filters.and(
          Filters.exists(MongoTaskEntity::lastFailure.name),
          Filters.or(
            // No success at all, or last success before boundary
            Filters.not(Filters.exists(MongoTaskEntity::lastSuccess.name)),
            Filters.lt(MongoTaskEntity::lastSuccess.name, boundary)
          ),
          // Last failure was before boundary (failing for longer than interval)
          Filters.lt(MongoTaskEntity::lastFailure.name, boundary)
        )
      ).map { toExecution(it) }
      .toList()
  }

  override suspend fun removeExecutions(taskName: String): Int = collection
    .deleteMany(
      Filters.eq(MongoTaskEntity::taskName.name, taskName)
    ).deletedCount
    .toInt()

  override suspend fun verifySupportsLockAndFetch() {
    logger.info("Mongo supports locking with #getAndLock")
  }

  override suspend fun createIndexes() {
    collection
      .createIndexes(
        listOf(
          IndexModel(Indexes.ascending(MongoTaskEntity::identity.name), IndexOptions().unique(true)),
          IndexModel(Indexes.ascending(MongoTaskEntity::picked.name), IndexOptions().name("idx_is_picked")),
          IndexModel(Indexes.ascending(MongoTaskEntity::executionTime.name), IndexOptions().name("idx_execution_time")),
          IndexModel(Indexes.ascending(MongoTaskEntity::lastHeartbeat.name), IndexOptions().name("idx_last_heartbeat")),
          IndexModel(Indexes.ascending(MongoTaskEntity::taskName.name), IndexOptions().name("idx_task_name"))
        )
      ).toList()
  }

  private suspend fun getOption(id: String): Option<MongoTaskEntity> =
    collection
      .find(Filters.eq(MongoTaskEntity::identity.name, id))
      .firstOrNull()
      .toOption()

  private fun toEntity(execution: Execution, metadata: Map<String, Any> = mapOf()): MongoTaskEntity = MongoTaskEntity(
    taskName = execution.taskName,
    taskInstance = execution.taskInstance.id,
    taskData = serializer.serialize(execution.taskInstance.data),
    executionTime = execution.executionTime,
    picked = execution.picked,
    pickedBy = execution.pickedBy,
    lastFailure = execution.lastFailure,
    lastSuccess = execution.lastSuccess,
    lastHeartbeat = execution.lastHeartbeat,
    consecutiveFailures = execution.consecutiveFailures,
    version = execution.version
  ).apply { metadata.forEach { (key, value) -> setMetadata(key, value) } }

  private fun toExecution(entity: MongoTaskEntity): Execution {
    val task = taskResolver.resolve(Resolvable.of(entity.taskName, entity.executionTime))
    // memoization?
    val dataSupplier = task.map { serializer.deserialize(it.dataClass, entity.taskData) }.orElse(null)
    val taskInstance = TaskInstance(entity.taskName, entity.taskInstance, dataSupplier)
    return Execution(
      entity.executionTime,
      taskInstance,
      entity.picked,
      entity.pickedBy,
      entity.lastSuccess,
      entity.lastFailure,
      entity.consecutiveFailures,
      entity.lastHeartbeat,
      entity.version
    )
  }

  private class UnresolvedFilter(
    private val unresolved: List<UnresolvedTask>
  ) {
    fun asFilter(): Bson = Filters.nin(MongoTaskEntity::taskName.name, unresolved.map { it.taskName })
  }

  private fun ScheduledExecutionsFilter.asFilterBson(): Bson {
    val filter = pickedValue
      .asArrow()
      .fold(
        { Filters.empty() },
        { Filters.eq(MongoTaskEntity::picked.name, it) }
      )

    if (!includeUnresolved && taskResolver.unresolved.isNotEmpty()) {
      val unresolvedFilter = Filters.nin(MongoTaskEntity::taskName.name, taskResolver.unresolved.map { it.taskName })
      return Filters.and(filter, unresolvedFilter)
    }
    return filter
  }

  companion object {
    private const val SCHEDULER_NAME_TAKE = 50
  }
}
