package io.github.osoykan.scheduler.couchbase

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
import io.github.osoykan.scheduler.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.time.*
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions")
class CouchbaseTaskRepository(
  private val clock: Clock,
  private val couchbase: Couchbase,
  private val taskResolver: TaskResolver,
  private val schedulerName: SchedulerName,
  private val serializer: Serializer
) : CoroutineTaskRepository {
  companion object {
    private const val SCHEDULER_NAME_TAKE = 50
    private const val SELECT_FROM_WITH_META = "SELECT c.*, { \"cas\": META(c).cas } AS metadata FROM"
    private const val MAX_RETRIES = 3
    private const val DEFAULT_RETRY_DELAY_MS = 100L
  }

  private val logger = LoggerFactory.getLogger(CouchbaseTaskRepository::class.java)
  private val collection: Collection by lazy { couchbase.schedulerCollection }
  private val Collection.fullName: String get() = "`${couchbase.bucketName}`.`${scope.name}`.`$name`"
  private val cluster: Cluster by lazy { couchbase.cluster }

  override suspend fun createIfNotExists(
    execution: ScheduledTaskInstance
  ): Boolean {
    val entity: CouchbaseTaskEntity = toEntity(Execution(execution.executionTime, execution.taskInstance)).copy(picked = false)
    return try {
      collection.insert(execution.documentId(), Content.binary(serializer.serialize(entity)))
      logger.debug("Successfully inserted task with id {}", execution.documentId())
      true
    } catch (_: DocumentExistsException) {
      logger.debug("Task with id {} already exists in the repository (concurrent insert)", execution.documentId())
      false
    } catch (e: CouchbaseException) {
      logger.warn("Failed to insert task with id {}: {}", execution.documentId(), e.message)
      if (logger.isDebugEnabled) {
        logger.debug("Full error details for failed task insertion:", e)
      }
      false
    }
  }

  override suspend fun createBatch(
    instances: List<ScheduledTaskInstance>
  ): Unit = coroutineScope {
    // Process in parallel with concurrency control
    instances
      .map { instance ->
        async {
          try {
            createIfNotExists(instance)
          } catch (e: Exception) {
            logger.error("Failed to create task instance {}", instance.id, e)
            false
          }
        }
      }.awaitAll()
  }

  override suspend fun getDue(now: Instant, limit: Int): List<Execution> {
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(
        " WHERE c.${TaskEntity::picked.name} = false " +
          "AND (c.${TaskEntity::executionTime.name} <= \$now OR c.${TaskEntity::executionTime.name} IS NULL)"
      )
      append(" ORDER BY c.${TaskEntity::executionTime.name}")
      append(" LIMIT \$limit")
    }
    return queryAsFlow<CouchbaseTaskEntity>(
      query,
      QueryParameters.named {
        param("limit", limit)
        param("now", now)
      }
    ).map { toExecution(it) }.toList()
  }

  override suspend fun replace(
    toBeReplaced: Execution,
    newInstance: ScheduledTaskInstance
  ): Instant = withRetry(MAX_RETRIES, DEFAULT_RETRY_DELAY_MS) {
    val newExecutionTime = newInstance.executionTime
    val newExecution = Execution(newExecutionTime, newInstance.taskInstance)
    getLockOption(toBeReplaced.documentId())
      .map { found ->
        Either
          .catch {
            collection.replace(
              toBeReplaced.documentId(),
              Content.binary(serializer.serialize(toEntity(newExecution, found.metadata))),
              cas = found.cas()
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
                throw RetryableException("Optimistic concurrency control conflict, retrying", it)
              }

              else -> {
                logger.error("Failed to replace task with id ${toBeReplaced.documentId()}", it)
                throw it
              }
            }
          }.merge()
      }.getOrElse { error("Task with id ${toBeReplaced.documentId()} not found") }
  }

  override suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    consumer: Consumer<Execution>
  ) {
    val pickedCondition = filter.asCondition(taskResolver)
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")

      if (pickedCondition.isNotEmpty()) {
        append(" WHERE $pickedCondition")
      }

      append(" ORDER BY c.${TaskEntity::executionTime.name}")
    }

    queryAsFlow<CouchbaseTaskEntity>(query)
      .map { toExecution(it) }
      .collect { consumer.accept(it) }
  }

  override suspend fun getScheduledExecutions(
    filter: ScheduledExecutionsFilter,
    taskName: String,
    consumer: Consumer<Execution>
  ) {
    val pickedCondition = filter.asCondition(taskResolver)
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(" WHERE c.${TaskEntity::taskName.name} = \$taskName")

      if (pickedCondition.isNotEmpty()) {
        append(" AND $pickedCondition")
      }

      append(" ORDER BY c.${TaskEntity::executionTime.name}")
    }

    queryAsFlow<CouchbaseTaskEntity>(
      query,
      parameters = QueryParameters.named {
        param("taskName", taskName)
      }
    ).map { toExecution(it) }.collect { consumer.accept(it) }
  }

  override suspend fun lockAndFetchGeneric(
    now: Instant,
    limit: Int
  ): List<Execution> {
    val unresolvedCondition = UnresolvedFilter(taskResolver.unresolved)
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(
        " WHERE c.${TaskEntity::picked.name} = false " +
          "AND (c.${TaskEntity::executionTime.name} <= \$now OR c.${TaskEntity::executionTime.name}  IS NULL)"
      )
      if (unresolvedCondition.unresolved.isNotEmpty()) {
        append(" AND $unresolvedCondition")
      }
      append(" ORDER BY c.${TaskEntity::executionTime.name} ")
      append(" LIMIT \$limit")
    }

    val candidates = queryAsFlow<CouchbaseTaskEntity>(
      query,
      parameters = QueryParameters.named {
        param("now", now)
        param("limit", limit)
      }
    ).map { toExecution(it) }.toList()

    val pickedBy = schedulerName.name.take(SCHEDULER_NAME_TAKE)
    val lastHeartbeat = clock.now()

    // Perform locking with concurrency control
    val updated = coroutineScope {
      candidates
        .map { candidate ->
          async {
            logger.info("Locking task with id {}", candidate.documentId())
            getLockAndUpdateWithRetry(candidate.documentId(), MAX_RETRIES, DEFAULT_RETRY_DELAY_MS) {
              it.copy(
                picked = true,
                pickedBy = pickedBy,
                lastHeartbeat = lastHeartbeat,
                version = it.version + 1
              )
            }
          }
        }.awaitAll()
        .filter { it.isSome() }
        .mapNotNull { it.getOrNull() }
    }

    if (updated.size != candidates.size) {
      logger.error(
        "Did not update same amount of executions that were locked in the transaction. " +
          "This might mean some assumption is wrong here, or that transaction is not working. " +
          "Needs to be investigated. Updated: ${updated.size}, expected: ${candidates.size}"
      )
    }
    return updated.map { toExecution(it).updateToPicked(pickedBy, lastHeartbeat) }
  }

  override suspend fun lockAndGetDue(
    now: Instant,
    limit: Int
  ): List<Execution> = lockAndFetchGeneric(now, limit)

  override suspend fun remove(execution: Execution) {
    Either
      .catch { collection.remove(execution.documentId()) }
      .mapLeft {
        when (it) {
          is DocumentNotFoundException -> logger.info("Failed to remove task with id ${execution.documentId()}, not found")
          else -> throw it
        }
      }
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
  ): Boolean = withRetry(MAX_RETRIES, DEFAULT_RETRY_DELAY_MS) {
    getLockAndUpdate(execution.documentId()) { it ->
      it
        .copy(
          picked = false,
          pickedBy = null,
          lastHeartbeat = null,
          lastSuccess = lastSuccess,
          lastFailure = lastFailure,
          executionTime = nextExecutionTime,
          consecutiveFailures = consecutiveFailures,
          version = it.version + 1
        ).let { data.map { d -> it.copy(taskData = serializer.serialize(d)) }.getOrElse { it } }
    }.isSome()
  }

  override suspend fun pick(
    e: Execution,
    timePicked: Instant
  ): Optional<Execution> = getLockAndUpdate(e.documentId()) {
    it.copy(
      picked = true,
      pickedBy = schedulerName.name.take(SCHEDULER_NAME_TAKE),
      version = it.version + 1,
      lastHeartbeat = timePicked
    )
  }.map { toExecution(it) }.asJava()

  override suspend fun getDeadExecutions(
    olderThan: Instant
  ): List<Execution> {
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(
        " WHERE c.${TaskEntity::picked.name} = true " +
          "AND (c.${TaskEntity::lastHeartbeat.name} <= \$olderThan or c.${TaskEntity::lastHeartbeat.name} IS NULL)"
      )
      append(" ORDER BY c.lastHeartbeat")
    }

    return queryAsFlow<CouchbaseTaskEntity>(
      query,
      QueryParameters.named {
        param("olderThan", olderThan)
      }
    ).map { toExecution(it) }.toList()
  }

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
  ): Boolean = getLockAndUpdateWithRetry(execution.documentId(), MAX_RETRIES, DEFAULT_RETRY_DELAY_MS) {
    it.copy(lastHeartbeat = heartbeatTime)
  }.isSome()

  override suspend fun getExecutionsFailingLongerThan(interval: Duration): List<Execution> {
    val query = buildString {
      append(SELECT_FROM_WITH_META)
      append(" ${collection.fullName} c")
      append(" WHERE (c.${TaskEntity::lastFailure.name} IS NOT NULL AND c.${TaskEntity::lastSuccess.name} IS NULL)")
      append(" OR (c.${TaskEntity::lastFailure.name} IS NOT NULL AND c.${TaskEntity::lastSuccess.name} < \$boundary)")
    }
    val boundary = clock.now().minus(interval)
    return queryAsFlow<CouchbaseTaskEntity>(
      query,
      QueryParameters.named {
        param("boundary", boundary)
      }
    ).map { toExecution(it) }.toList()
  }

  override suspend fun removeExecutions(taskName: String): Int {
    val query = buildString {
      append("DELETE FROM")
      append(" ${collection.fullName} c")
      append(" WHERE c.${TaskEntity::taskName.name} = \$taskName")
      append(" RETURNING *")
    }
    val result = cluster
      .query(
        query,
        readonly = false,
        parameters = QueryParameters.named { param("taskName", taskName) }
      ).execute()
      .rows
      .count()
    return result
  }

  override suspend fun verifySupportsLockAndFetch() {
    logger.debug("Couchbase supports locking with #getAndLock")
  }

  override suspend fun createIndexes(): Unit = coroutineScope {
    cluster.waitForKeySpaceAvailability(couchbase.bucketName, couchbase.schedulerCollection.name, 30.seconds, logger = { logger.info(it) })

    data class Index(
      val name: String,
      val fields: List<String>,
      val ignoreIfExists: Boolean = true
    )
    listOf(
      Index("idx_is_picked_executionTime", listOf(TaskEntity::picked.name, TaskEntity::executionTime.name)),
      Index("idx_is_picked_lastHeartbeat", listOf(TaskEntity::picked.name, TaskEntity::lastHeartbeat.name)),
      Index("idx_execution_time", listOf(TaskEntity::executionTime.name)),
      Index("idx_last_heartbeat", listOf(TaskEntity::lastHeartbeat.name)),
      Index("idx_task_name", listOf(TaskEntity::taskName.name)),
      Index("idx_identity_name", listOf(TaskEntity::taskName.name, TaskEntity::taskInstance.name)),
      Index("idx_identity", listOf(TaskEntity::identity.name))
    ).onEach {
      logger.debug("Creating index {}", it.name)
      collection.waitUntilIndexIsCreated(logger = { m -> logger.debug(m) }) {
        queryIndexes.createIndex(
          indexName = it.name,
          fields = it.fields,
          ignoreIfExists = it.ignoreIfExists,
          numReplicas = 0,
          deferred = true
        )
      }
      logger.debug("Index {} created", it.name)
    }
  }

  private inline fun <reified T> queryAsFlow(
    query: String,
    parameters: QueryParameters = QueryParameters.None
  ) = flow {
    cluster
      .query(
        query,
        parameters = parameters,
        readonly = true,
        profile = QueryProfile.OFF,
        consistency = QueryScanConsistency.requestPlus()
      ).execute {
        emit(serializer.deserialize(T::class.java, it.content))
      }
  }

  private suspend fun getOption(id: String): Option<CouchbaseTaskEntity> =
    Either
      .catch { collection.get(id) }
      .mapLeft {
        when (it) {
          is DocumentNotFoundException -> None
          else -> throw it
        }
      }.map { result ->
        val entity = result.contentAs<CouchbaseTaskEntity>()
        entity.cas(result.cas)
        entity
      }.getOrNone()

  private suspend fun getLockOption(
    id: String,
    duration: kotlin.time.Duration = 10.seconds
  ): Option<CouchbaseTaskEntity> = Either
    .catch { collection.getAndLock(id, duration) }
    .mapLeft {
      when (it) {
        is DocumentNotFoundException -> None
        is DocumentLockedException -> {
          logger.debug("Document $id is already locked by another operation")
          throw RetryableException("Document is already locked by another operation", it)
        }
        else -> throw it
      }
    }.map { result ->
      val entity = result.contentAs<CouchbaseTaskEntity>()
      entity.cas(result.cas)
      entity
    }.getOrNone()

  private suspend fun getLockAndUpdate(
    id: String,
    duration: kotlin.time.Duration = 10.seconds,
    block: (CouchbaseTaskEntity) -> CouchbaseTaskEntity
  ): Option<CouchbaseTaskEntity> = Either
    .catch {
      getLockOption(id, duration)
        .map(block)
        .map { updated ->
          val res = collection.replace(id, Content.binary(serializer.serialize(updated)), cas = updated.cas())
          Pair(updated, res)
        }
    }.map { o -> o.map { it.first } }
    .mapLeft {
      when (it) {
        is DocumentNotFoundException -> None.also { logger.warn("Failed to update task with id $id, not found") }
        is CasMismatchException -> None.also { logger.warn("Failed to update task with id $id, cas mismatch") }
        is RetryableException -> throw it
        else -> throw it
      }
    }.merge()

  private suspend fun getLockAndUpdateWithRetry(
    id: String,
    maxRetries: Int = MAX_RETRIES,
    delayMs: Long = DEFAULT_RETRY_DELAY_MS,
    duration: kotlin.time.Duration = 10.seconds,
    block: (CouchbaseTaskEntity) -> CouchbaseTaskEntity
  ): Option<CouchbaseTaskEntity> = withRetry(maxRetries, delayMs) {
    getLockAndUpdate(id, duration, block)
  }

  private suspend fun <T> withRetry(
    maxRetries: Int,
    delayMs: Long,
    block: suspend () -> T
  ): T {
    var retries = 0
    var lastException: Exception? = null

    while (retries < maxRetries) {
      try {
        return block()
      } catch (e: RetryableException) {
        lastException = e
        retries++
        if (retries < maxRetries) {
          logger.debug("Retrying operation after failure ($retries/$maxRetries): ${e.message}")
          delay(delayMs * (1L shl (retries - 1))) // Exponential backoff
        }
      } catch (e: CasMismatchException) {
        lastException = e
        retries++
        if (retries < maxRetries) {
          logger.debug("CAS mismatch, retrying operation ($retries/$maxRetries)")
          delay(delayMs * (1L shl (retries - 1))) // Exponential backoff
        }
      }
    }

    throw lastException ?: RuntimeException("Operation failed after $maxRetries retries")
  }

  /**
   * Exception used to mark operations that can be retried
   */
  private class RetryableException(
    message: String,
    cause: Throwable? = null
  ) : RuntimeException(message, cause)

  private fun toEntity(execution: Execution, metadata: Map<String, Any> = mapOf()): CouchbaseTaskEntity = CouchbaseTaskEntity(
    taskName = execution.taskName,
    taskInstance = execution.taskInstance.id,
    taskData = serializer.serialize(execution.taskInstance.data),
    executionTime = execution.executionTime,
    picked = execution.picked,
    pickedBy = execution.pickedBy,
    lastFailure = execution.lastFailure,
    lastSuccess = execution.lastSuccess,
    lastHeartbeat = execution.lastHeartbeat,
    version = execution.version,
    consecutiveFailures = execution.consecutiveFailures
  ).apply { metadata.forEach { (key, value) -> setMetadata(key, value) } }

  private fun toExecution(entity: CouchbaseTaskEntity): Execution {
    val task = taskResolver.resolve(entity.taskName)
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
    val unresolved: List<UnresolvedTask>
  ) {
    override fun toString(): String =
      "c.${TaskEntity::taskName.name} not in (${unresolved.joinToString(", ") { "'${it.taskName}'" }})"
  }

  private suspend fun Cluster.waitForKeySpaceAvailability(
    bucketName: String,
    keyspaceName: String,
    duration: kotlin.time.Duration,
    delayMillis: Long = 1000,
    logger: (log: String) -> Unit = ::println
  ): Unit = waitUntilSucceeds(
    continueIf = { it is CollectionNotFoundException },
    duration = duration,
    delayMillis = delayMillis,
    logger = logger
  ) { bucket(bucketName).defaultScope().collection(keyspaceName).exists("not-important") }

  private suspend fun Collection.waitUntilIndexIsCreated(
    duration: kotlin.time.Duration = 1.minutes,
    delayMillis: Long = 50,
    logger: (log: String) -> Unit = ::println,
    indexOps: suspend Collection.() -> Unit
  ): Unit = waitUntilSucceeds(
    continueIf = { it is IndexFailureException || it is InternalServerFailureException || it is UnambiguousTimeoutException },
    duration = duration,
    delayMillis = delayMillis,
    logger = logger
  ) { indexOps(this) }

  private suspend fun waitUntilSucceeds(
    continueIf: (Throwable) -> Boolean,
    duration: kotlin.time.Duration = 10.minutes,
    delayMillis: Long = 50,
    logger: (log: String) -> Unit = ::println,
    block: suspend () -> Unit
  ) {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < duration.inWholeMilliseconds) {
      val executed = try {
        block()
        true
      } catch (e: Throwable) {
        logger("Operation failed.\nBecause of: $e")
        when {
          continueIf(e) -> false
          else -> throw e
        }
      }

      if (executed) {
        logger("Operation executed successfully")
        return
      }

      logger("Operation is not successful. Waiting for $delayMillis ms...")
      delay(delayMillis)
    }

    throw TimeoutException("Timed out waiting for the operation!")
  }

  private fun ScheduledExecutionsFilter.asCondition(taskResolver: TaskResolver): String = buildString {
    pickedValue
      .asArrow()
      .map { picked ->
        append("c.${TaskEntity::picked.name} = $picked")
        if (!includeUnresolved && taskResolver.unresolved.isNotEmpty()) append(" AND ")
      }

    if (!includeUnresolved && taskResolver.unresolved.isNotEmpty()) {
      append("c.${TaskEntity::taskName.name} NOT IN (")
      append(taskResolver.unresolved.joinToString(", ") { "'${it.taskName}'" })
      append(")")
    }
  }
}
