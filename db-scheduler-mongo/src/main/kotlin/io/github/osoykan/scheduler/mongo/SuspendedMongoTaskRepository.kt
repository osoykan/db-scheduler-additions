package io.github.osoykan.scheduler.mongo

import arrow.core.*
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Clock
import com.github.kagkarlsson.scheduler.TaskResolver.UnresolvedTask
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException
import com.github.kagkarlsson.scheduler.serializer.Serializer
import com.github.kagkarlsson.scheduler.task.*
import com.mongodb.client.model.*
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.*
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory
import java.time.*
import java.util.function.*
import kotlin.jvm.optionals.getOrElse

class SuspendedMongoTaskRepository(
  private val clock: Clock,
  private val mongo: Mongo,
  private val taskResolver: TaskResolver,
  private val schedulerName: SchedulerName,
  private val serializer: Serializer
) {
  private val logger = LoggerFactory.getLogger(SuspendedMongoTaskRepository::class.java)
  private val collection: MongoCollection<TaskEntity> by lazy { mongo.schedulerCollection }

  suspend fun createIfNotExists(
    execution: SchedulableInstance<*>
  ): Boolean = getOption(execution.documentId())
    .map {
      logger.debug("Task with id {} already exists in the repository. Due:{}", execution.documentId(), it.executionTime)
      false
    }.recover {
      val entity = toEntity(Execution(execution.getNextExecutionTime(clock.now()), execution.taskInstance))
        .copy(picked = false)
      collection.insertOne(entity).wasAcknowledged()
    }.getOrElse { false }

  suspend fun getDue(now: Instant, limit: Int): List<Execution> = collection.find(
    Filters.and(
      Filters.eq(TaskEntity::picked.name, false),
      Filters.lte(TaskEntity::executionTime.name, now)
    )
  ).limit(limit).map { toExecution(it) }.toList()

  suspend fun replace(
    toBeReplaced: Execution,
    newInstance: SchedulableInstance<*>
  ): Instant {
    val newExecutionTime = newInstance.getNextExecutionTime(clock.now())
    val newExecution = Execution(newExecutionTime, newInstance.taskInstance)
    return getOption(toBeReplaced.documentId())
      .map { found ->
        collection.replaceOne(
          Filters.and(
            Filters.eq(TaskEntity::identity.name, toBeReplaced.documentId()),
            Filters.eq(TaskEntity::version.name, found.version)
          ),
          toEntity(newExecution, found.internalMetadata).copy(
            version = found.version + 1
          )
        )
        newExecutionTime
      }.getOrElse {
        throw TaskInstanceException(
          "Task with id ${toBeReplaced.documentId()} not found",
          toBeReplaced.taskName,
          toBeReplaced.taskInstance.id
        )
      }
  }

  suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    consumer: Consumer<Execution>
  ) {
    val f = filter.pickedValue.map { Filters.eq(TaskEntity::picked.name, it) }.getOrElse { Filters.empty() }
    collection
      .find(f)
      .sort(Sorts.ascending("executionTime"))
      .map { toExecution(it) }
      .collect { consumer.accept(it) }
  }

  suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    taskName: String,
    consumer: Consumer<Execution>
  ) {
    val f = filter.pickedValue.map { Filters.eq(TaskEntity::picked.name, it) }.getOrElse { Filters.empty() }
    collection
      .find(Filters.and(Filters.eq(TaskEntity::taskName.name, taskName), f))
      .sort(Sorts.ascending(TaskEntity::executionTime.name))
      .map { toExecution(it) }
      .collect { consumer.accept(it) }
  }

  suspend fun lockAndFetchGeneric(
    now: Instant,
    limit: Int
  ): List<Execution> {
    val unresolvedCondition = UnresolvedFilter(taskResolver.unresolved)
    val filter = Filters.and(
      Filters.eq(TaskEntity::picked.name, false),
      Filters.lte(TaskEntity::executionTime.name, now),
      unresolvedCondition.asFilter()
    )
    val pickedBy = schedulerName.name.take(SCHEDULER_NAME_TAKE)
    val lastHeartbeat = clock.now()
    return collection.find(filter)
      .limit(limit)
      .map { toExecution(it) }
      .map { execution ->
        logger.debug("#lockAndFetchGeneric task with id {}", execution.documentId())
        val updated = collection.findOneAndUpdate(
          Filters.and(
            Filters.eq(TaskEntity::identity.name, execution.documentId()),
            Filters.eq(TaskEntity::picked.name, false),
            Filters.lte(TaskEntity::executionTime.name, now),
            unresolvedCondition.asFilter()
          ),
          Updates.combine(
            Updates.set(TaskEntity::picked.name, true),
            Updates.set(TaskEntity::pickedBy.name, pickedBy),
            Updates.set(TaskEntity::lastHeartbeat.name, lastHeartbeat),
            Updates.inc(TaskEntity::version.name, 1)
          ),
          FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
        )
        updated?.let { toExecution(it) }?.updateToPicked(pickedBy, lastHeartbeat)
      }.filterNotNull().toList()
  }

  suspend fun lockAndGetDue(
    now: Instant,
    limit: Int
  ): List<Execution> = lockAndFetchGeneric(now, limit)

  suspend fun remove(execution: Execution) {
    collection.deleteOne(Filters.eq("identity", execution.documentId()))
  }

  suspend fun reschedule(
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

  suspend fun reschedule(
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
    val newExecution = Execution(nextExecutionTime, execution.taskInstance)
    return getOption(execution.documentId())
      .map { found ->
        collection.replaceOne(
          Filters.and(
            Filters.eq(TaskEntity::identity.name, execution.documentId()),
            Filters.eq(TaskEntity::version.name, found.version)
          ),
          toEntity(newExecution, found.internalMetadata).copy(
            lastSuccess = lastSuccess,
            lastFailure = lastFailure,
            consecutiveFailures = consecutiveFailures,
            taskData = data.map { serializer.serialize(it) }.getOrElse { found.taskData },
            version = found.version + 1
          )
        )
        true
      }.getOrElse { false }
  }

  suspend fun pick(
    execution: Execution,
    timePicked: Instant
  ): Option<Execution> = getOption(execution.documentId())
    .map { found ->
      collection.replaceOne(
        Filters.and(
          Filters.eq(TaskEntity::identity.name, execution.documentId()),
          Filters.eq(TaskEntity::version.name, found.version)
        ),
        toEntity(execution, found.internalMetadata).copy(
          picked = true,
          pickedBy = schedulerName.name.take(SCHEDULER_NAME_TAKE),
          lastHeartbeat = timePicked,
          version = found.version + 1
        )
      )
      execution
    }

  suspend fun getDeadExecutions(
    olderThan: Instant
  ): List<Execution> = collection.find(
    Filters.and(
      Filters.eq(TaskEntity::picked.name, true),
      Filters.lt(TaskEntity::lastHeartbeat.name, olderThan)
    )
  ).sort(Sorts.ascending(TaskEntity::lastHeartbeat.name))
    .map { toExecution(it) }
    .toList()

  suspend fun updateHeartbeatWithRetry(
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

  suspend fun getExecution(taskName: String, taskInstanceId: String): Option<Execution> =
    getOption(TaskEntity.documentId(taskName, taskInstanceId))
      .map { toExecution(it) }

  suspend fun updateHeartbeat(
    execution: Execution,
    heartbeatTime: Instant
  ): Boolean = getOption(execution.documentId())
    .map { found ->
      collection.replaceOne(
        Filters.and(
          Filters.eq(TaskEntity::identity.name, execution.documentId()),
          Filters.eq(TaskEntity::version.name, found.version)
        ),
        toEntity(execution, found.internalMetadata)
          .copy(
            lastHeartbeat = heartbeatTime,
            version = found.version + 1
          )
      )
      true
    }.getOrElse { false }

  suspend fun getExecutionsFailingLongerThan(interval: Duration): List<Execution> {
    val boundary = clock.now().minus(interval)
    return collection.find(
      Filters.or(
        Filters.and(
          Filters.exists(TaskEntity::lastFailure.name),
          Filters.lt(TaskEntity::lastSuccess.name, boundary)
        ),
        Filters.and(
          Filters.exists(TaskEntity::lastFailure.name),
          Filters.lt(TaskEntity::lastFailure.name, boundary)
        )
      )
    ).map { toExecution(it) }.toList()
  }

  suspend fun removeExecutions(taskName: String): Int = collection.deleteMany(
    Filters.eq(TaskEntity::taskName.name, taskName)
  ).deletedCount.toInt()

  fun verifySupportsLockAndFetch() {
    logger.info("Couchbase supports locking with #getAndLock")
  }

  suspend fun createIndexes() {
    collection.createIndexes(
      listOf(
        IndexModel(Indexes.ascending(TaskEntity::identity.name), IndexOptions().unique(true)),
        IndexModel(Indexes.ascending(TaskEntity::picked.name), IndexOptions().name("idx_is_picked")),
        IndexModel(Indexes.ascending(TaskEntity::executionTime.name), IndexOptions().name("idx_execution_time")),
        IndexModel(Indexes.ascending(TaskEntity::lastHeartbeat.name), IndexOptions().name("idx_last_heartbeat")),
        IndexModel(Indexes.ascending(TaskEntity::taskName.name), IndexOptions().name("idx_task_name"))
      )
    ).toList()
  }

  private suspend fun getOption(id: String): Option<TaskEntity> =
    collection.find(Filters.eq(TaskEntity::identity.name, id))
      .firstOrNull()
      .toOption()

  private fun toEntity(execution: Execution, metadata: Map<String, Any> = mapOf()): TaskEntity = TaskEntity(
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

  private fun toExecution(entity: TaskEntity): Execution {
    val task = taskResolver.resolve(entity.taskName)
    val dataSupplier = memoize {
      task.map { serializer.deserialize(it.dataClass, entity.taskData) }.orElse(null)
    }

    val taskInstance = TaskInstance(entity.taskName, entity.taskInstance, dataSupplier)
    return Execution(entity.executionTime, taskInstance)
  }

  private class UnresolvedFilter(private val unresolved: List<UnresolvedTask>) {
    fun asFilter(): Bson = Filters.nin(TaskEntity::taskName.name, unresolved.map { it.taskName })
  }

  private fun <T> memoize(original: Supplier<T>): Supplier<T> {
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

  companion object {
    private const val SCHEDULER_NAME_TAKE = 50
  }
}
