package com.github.kagkarlsson.scheduler.couchbase

import arrow.core.*
import com.couchbase.client.core.error.*
import com.couchbase.client.kotlin.*
import com.couchbase.client.kotlin.Collection
import com.couchbase.client.kotlin.codec.Content
import com.couchbase.client.kotlin.query.*
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Clock
import com.github.kagkarlsson.scheduler.TaskResolver.UnresolvedTask
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException
import com.github.kagkarlsson.scheduler.serializer.Serializer
import com.github.kagkarlsson.scheduler.task.*
import org.slf4j.LoggerFactory
import java.time.*
import java.util.function.*
import kotlin.time.Duration.Companion.seconds

data class Couchbase(
  val cluster: Cluster,
  val bucketName: String
) {
  val defaultCollection: Collection by lazy { cluster.bucket(bucketName).defaultCollection() }
}

@Suppress("TooManyFunctions")
class SuspendedCouchbaseTaskRepository(
  private val clock: Clock,
  private val couchbase: Couchbase,
  private val taskResolver: TaskResolver,
  private val schedulerName: SchedulerName,
  private val serializer: Serializer
) {
  companion object {
    private const val SCHEDULER_NAME_TAKE = 50
    private const val SELECT_FROM_WITH_META = "SELECT c.*, { \"cas\": META(c).cas } AS metadata FROM"
  }

  private val logger = LoggerFactory.getLogger(SuspendedCouchbaseTaskRepository::class.java)
  private val collection: Collection by lazy { couchbase.defaultCollection }
  private val Collection.fullName: String get() = "`${couchbase.bucketName}`.`${scope.name}`.`$name`"
  private val cluster: Cluster by lazy { couchbase.cluster }

  @Suppress("SwallowedException")
  suspend fun createIfNotExists(
    execution: SchedulableInstance<*>
  ): Boolean = getOption(execution.documentId())
    .map {
      logger.info("Task with id {} already exists in the repository. Due:{}", execution.documentId(), it.executionTime)
      false
    }.recover {
      val entity = toEntity(Execution(execution.getNextExecutionTime(clock.now()), execution.taskInstance)).copy(isPicked = false)
      try {
        collection.insert(execution.documentId(), Content.binary(serializer.serialize(entity)))
        true
      } catch (e: DocumentExistsException) {
        logger.info("Task with id {} already exists in the repository", execution.id)
        false
      } catch (e: CouchbaseException) {
        logger.info("Failed to insert task with id {}, probably an internal error", execution.id, e)
        false
      }
    }.getOrElse { false }

  suspend fun getDue(now: Instant, limit: Int): List<Execution> {
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(" WHERE c.isPicked = false AND (c.executionTime <= \$now OR c.executionTime IS NULL)")
      append(" ORDER BY c.executionTime")
      append(" LIMIT \$limit")
    }
    return queryFor<TaskEntity>(
      query,
      QueryParameters.named(mapOf("now" to now, "limit" to limit))
    ).map { toExecution(it) }
  }

  @Suppress("ThrowsCount")
  suspend fun replace(
    toBeReplaced: Execution,
    newInstance: SchedulableInstance<*>
  ): Instant {
    val newExecutionTime = newInstance.getNextExecutionTime(clock.now())
    val newExecution = Execution(newExecutionTime, newInstance.taskInstance)
    return getLockOption(toBeReplaced.documentId())
      .map { found ->
        Either.catch {
          collection.replace(
            toBeReplaced.documentId(),
            Content.binary(serializer.serialize(toEntity(newExecution, found.internalMetadata))),
            cas = found.cas()!!
          )
          newExecutionTime
        }.mapLeft {
          when (it) {
            is DocumentNotFoundException -> {
              logger.warn("Failed to replace task with id ${toBeReplaced.documentId()}, not found")
              throw TaskInstanceException(
                "Task with id ${toBeReplaced.documentId()} not found",
                toBeReplaced.taskInstance.taskName,
                toBeReplaced.taskInstance.id
              )
            }

            is CasMismatchException -> {
              logger.warn("Failed to replace task with id ${toBeReplaced.documentId()}, cas mismatch")
              throw TaskInstanceException(
                "Task with id ${toBeReplaced.documentId()} was updated by another process",
                toBeReplaced.taskInstance.taskName,
                toBeReplaced.taskInstance.id
              )
            }

            else -> {
              logger.error("Failed to replace task with id ${toBeReplaced.documentId()}", it)
              throw it
            }
          }
        }.merge()
      }.getOrElse { error("Task with id ${toBeReplaced.documentId()} not found") }
  }

  suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    consumer: Consumer<Execution>
  ) {
    val pickedCondition = filter.pickedValue.asArrow().map { "c.isPicked = $it" }.getOrElse { "" }
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")

      if (pickedCondition.isNotEmpty()) {
        append(" WHERE $pickedCondition")
      }

      append(" ORDER BY c.executionTime")
    }

    queryFor<TaskEntity>(query)
      .map { toExecution(it) }
      .forEach { consumer.accept(it) }
  }

  suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    taskName: String,
    consumer: Consumer<Execution>
  ) {
    val pickedCondition = filter.pickedValue.asArrow().map { "c.isPicked = $it" }.getOrElse { "" }
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(" WHERE c.taskName = \$taskName")

      if (pickedCondition.isNotEmpty()) {
        append(" AND $pickedCondition")
      }

      append(" ORDER BY c.executionTime")
    }

    queryFor<TaskEntity>(
      query,
      parameters = QueryParameters.named(mapOf("taskName" to taskName))
    ).map { toExecution(it) }.forEach { consumer.accept(it) }
  }

  suspend fun lockAndFetchGeneric(
    now: Instant,
    limit: Int
  ): List<Execution> {
    val unresolvedCondition = UnresolvedFilter(taskResolver.unresolved)
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(" WHERE c.isPicked = false AND (c.executionTime <= \$now OR c.executionTime IS NULL)")
      if (unresolvedCondition.unresolved.isNotEmpty()) {
        append(" AND $unresolvedCondition")
      }
      append(" ORDER BY c.executionTime")
      append(" LIMIT \$limit")
    }

    val candidates = queryFor<TaskEntity>(
      query,
      parameters = QueryParameters.named(
        mapOf(
          "now" to now,
          "limit" to limit
        )
      )
    ).map { toExecution(it) }

    val pickedBy = schedulerName.name.take(SCHEDULER_NAME_TAKE)
    val lastHeartbeat = clock.now()

    val updated = candidates.map { candidate ->
      logger.info("Locking task with id {}", candidate.documentId())
      getLockAndUpdate(candidate.documentId()) {
        it.copy(
          isPicked = true,
          pickedBy = pickedBy,
          lastHeartbeat = lastHeartbeat
        )
      }
    }.filter { it.isSome() }.mapNotNull { it.getOrNull() }

    if (updated.size != candidates.size) {
      logger.error(
        "Did not update same amount of executions that were locked in the transaction. " +
          "This might mean some assumption is wrong here, or that transaction is not working. " +
          "Needs to be investigated. Updated: ${updated.size}, expected: ${candidates.size}"
      )
    }
    return updated.map { toExecution(it).updateToPicked(pickedBy, lastHeartbeat) }
  }

  suspend fun lockAndGetDue(
    now: Instant,
    limit: Int
  ): List<Execution> = lockAndFetchGeneric(now, limit)

  suspend fun remove(execution: Execution) {
    Either.catch { collection.remove(execution.documentId()) }
      .mapLeft {
        when (it) {
          is DocumentNotFoundException -> logger.info("Failed to remove task with id ${execution.documentId()}, not found")
          else -> throw it
        }
      }
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
  ): Boolean = getLockAndUpdate(execution.documentId()) {
    it.copy(
      isPicked = false,
      pickedBy = null,
      lastHeartbeat = null,
      lastSuccess = lastSuccess,
      lastFailure = lastFailure,
      executionTime = nextExecutionTime,
      consecutiveFailures = consecutiveFailures
    ).let { data.map { d -> it.copy(taskData = serializer.serialize(d)) }.getOrElse { it } }
  }.isSome()

  suspend fun pick(
    execution: Execution,
    timePicked: Instant
  ): Option<Execution> = getLockAndUpdate(execution.documentId()) {
    it.copy(
      isPicked = true,
      pickedBy = schedulerName.name.take(SCHEDULER_NAME_TAKE),
      lastHeartbeat = timePicked
    )
  }.map { toExecution(it) }

  suspend fun getDeadExecutions(
    olderThan: Instant
  ): List<Execution> {
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(" WHERE c.isPicked = true AND (c.lastHeartbeat <= \$olderThan or c.lastHeartbeat IS NULL)")
      append(" ORDER BY c.lastHeartbeat")
    }

    return queryFor<TaskEntity>(query, QueryParameters.named(mapOf("olderThan" to olderThan)))
      .map { toExecution(it) }
  }

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

  suspend fun getExecution(taskName: String, taskInstanceId: String): Execution {
    val result = collection.get(TaskEntity.documentId(taskName, taskInstanceId))
    val taskEntity = serializer.deserialize(TaskEntity::class.java, result.content.bytes)
    return toExecution(taskEntity)
  }

  suspend fun updateHeartbeat(
    execution: Execution,
    heartbeatTime: Instant
  ): Boolean = getLockAndUpdate(execution.documentId()) {
    it.copy(lastHeartbeat = heartbeatTime)
  }.isSome()

  suspend fun getExecutionsFailingLongerThan(interval: Duration): List<Execution> {
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(" WHERE (c.lastFailure IS NOT NULL AND c.lastSuccess IS NULL)")
      append(" OR (c.lastFailure IS NOT NULL AND c.lastSuccess < \$boundary)")
    }
    val boundary = clock.now().minus(interval)
    return queryFor<TaskEntity>(query, QueryParameters.named(mapOf("boundary" to boundary)))
      .map { toExecution(it) }
  }

  suspend fun removeExecutions(taskName: String): Int {
    val query = buildString {
      append("DELETE FROM")
      append(" ${collection.fullName} c")
      append(" WHERE c.taskName = \$taskName")
      append(" RETURNING *")
    }
    val result = cluster.query(
      query,
      readonly = false,
      parameters = QueryParameters.named(mapOf("taskName" to taskName)),
      consistency = QueryScanConsistency.requestPlus()
    ).execute().rows.count()
    return result
  }

  fun verifySupportsLockAndFetch() {
    logger.info("Couchbase supports locking with #getAndLock")
  }

  suspend fun createIndexes() {
    val indexes = mapOf(
      "idx_is_picked" to suspend {
        collection.queryIndexes.createIndex(
          indexName = "idx_is_picked",
          fields = listOf("isPicked"),
          ignoreIfExists = true
        )
      },
      "idx_execution_time" to suspend {
        collection.queryIndexes.createIndex(
          indexName = "idx_execution_time",
          fields = listOf("executionTime"),
          ignoreIfExists = true
        )
      },
      "idx_last_heartbeat" to suspend {
        collection.queryIndexes.createIndex(
          indexName = "idx_last_heartbeat",
          fields = listOf("lastHeartbeat"),
          ignoreIfExists = true
        )
      },
      "idx_task_name" to suspend {
        collection.queryIndexes.createIndex(
          indexName = "idx_task_name",
          fields = listOf("taskName"),
          ignoreIfExists = true
        )
      }
    )
    indexes.forEach {
      logger.info("Creating index {}", it.key)
      indexes.getValue(it.key)()
      logger.info("Index {} created", it.key)
    }
  }

  private suspend inline fun <reified T> queryFor(
    query: String,
    parameters: QueryParameters = QueryParameters.None
  ): List<T> = cluster.query(
    query,
    consistency = QueryScanConsistency.requestPlus(),
    parameters = parameters,
    readonly = true
  ).execute()
    .rows
    .mapNotNull { serializer.deserialize(T::class.java, it.content) }

  private suspend fun getOption(id: String): Option<TaskEntity> =
    Either.catch { collection.get(id) }
      .mapLeft {
        when (it) {
          is DocumentNotFoundException -> None
          else -> throw it
        }
      }.map { result ->
        val entity = result.contentAs<TaskEntity>()
        entity.cas(result.cas)
        entity
      }.getOrNone()

  private suspend fun getLockOption(
    id: String,
    duration: kotlin.time.Duration = 10.seconds
  ): Option<TaskEntity> = Either.catch { collection.getAndLock(id, duration) }
    .mapLeft {
      when (it) {
        is DocumentNotFoundException -> None
        else -> throw it
      }
    }.map { result ->
      val entity = result.contentAs<TaskEntity>()
      entity.cas(result.cas)
      entity
    }.getOrNone()

  private suspend fun getLockAndUpdate(
    id: String,
    duration: kotlin.time.Duration = 10.seconds,
    block: (TaskEntity) -> TaskEntity
  ): Option<TaskEntity> = Either.catch {
    getLockOption(id, duration)
      .map(block)
      .map { updated ->
        val res = collection.replace(id, Content.binary(serializer.serialize(updated)), cas = updated.cas()!!)
        Pair(updated, res)
      }
  }.map { o -> o.map { it.first } }
    .mapLeft {
      when (it) {
        is DocumentNotFoundException -> None.also { logger.warn("Failed to update task with id $id, not found") }
        is CasMismatchException -> None.also { logger.warn("Failed to update task with id $id, cas mismatch") }
        else -> throw it
      }
    }.merge()

  private fun toEntity(execution: Execution, metadata: Map<String, Any> = mapOf()): TaskEntity = TaskEntity(
    taskName = execution.taskName,
    taskInstance = execution.taskInstance.id,
    taskData = serializer.serialize(execution.taskInstance.data),
    executionTime = execution.executionTime,
    isPicked = execution.picked,
    pickedBy = execution.pickedBy,
    lastFailure = execution.lastFailure,
    lastSuccess = execution.lastSuccess,
    lastHeartbeat = execution.lastHeartbeat,
    consecutiveFailures = execution.consecutiveFailures
  ).apply { metadata.forEach { (key, value) -> setMetadata(key, value) } }

  private fun toExecution(entity: TaskEntity): Execution {
    val task = taskResolver.resolve(entity.taskName)
    val dataSupplier = memoize {
      task.map { serializer.deserialize(it.dataClass, entity.taskData) }.orElse(null)
    }

    val taskInstance = TaskInstance(entity.taskName, entity.taskInstance, dataSupplier)
    return Execution(entity.executionTime, taskInstance)
  }

  private class UnresolvedFilter(val unresolved: List<UnresolvedTask>) {
    override fun toString(): String = "taskName not in (${unresolved.joinToString(", ") { "'${it.taskName}'" }})"
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
}
