package io.github.osoykan.scheduler.mongo

import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Waiter
import com.github.kagkarlsson.scheduler.event.SchedulerListeners
import com.github.kagkarlsson.scheduler.stats.*
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.mongodb.kotlin.client.coroutine.*
import io.github.osoykan.scheduler.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import kotlin.coroutines.CoroutineContext
import kotlin.time.toJavaDuration

data class Mongo(
  val client: MongoClient,
  val database: String,
  override val collection: String = "scheduler"
) : DocumentDatabase<Mongo> {
  private val databaseOps = client.getDatabase(database)
  val schedulerCollection: MongoCollection<MongoTaskEntity> by lazy { databaseOps.getCollection(collection) }

  override suspend fun ensureCollectionExists() {
    val exists = databaseOps.listCollectionNames().toList().any { it == collection }
    if (exists) return
    databaseOps.createCollection(collection)
  }

  override fun withCollection(collection: String): Mongo = copy(collection = collection)
}

@SchedulerDslMarker
class MongoSchedulerDsl : SchedulerDsl<Mongo>() {
  private var executorService: ExecutorService? = null
  private var houseKeeperExecutorService: ScheduledExecutorService? = null
  private var schedulerScope: CoroutineScope? = null

  internal fun build(): Scheduler {
    requireNotNull(database) { "Database must be provided" }

    val statsRegistry = MicrometerStatsRegistry(meterRegistry, knownTasks + startupTasks)
    val listeners = listeners + StatsRegistryAdapter(statsRegistry)
    val taskResolver = TaskResolver(SchedulerListeners(listeners), clock, knownTasks + startupTasks)

    houseKeeperExecutorService = Executors.newScheduledThreadPool(
      corePoolSize,
      NamedThreadFactory("db-scheduler-house-keeper-executor-$name")
    )

    val supervisorJob = SupervisorJob()
    schedulerScope = CoroutineScope(createCoroutineContext(supervisorJob))
    executorService = schedulerScope!!.asExecutorService

    val taskRepository = KTaskRepository(
      MongoTaskRepository(clock, database!!, taskResolver, SchedulerName.Fixed(name), serializer),
      schedulerScope!!,
      clock
    ).also { it.createIndexes() }

    return MongoScheduler(
      clock = clock,
      schedulerTaskRepository = taskRepository,
      clientTaskRepository = taskRepository,
      taskResolver = taskResolver,
      schedulerName = SchedulerName.Fixed(name),
      threadPoolSize = corePoolSize,
      executorService = executorService!!,
      houseKeeperExecutorService = houseKeeperExecutorService!!,
      deleteUnresolvedAfter = deleteUnresolvedAfter,
      executeDueWaiter = Waiter(executeDue.toJavaDuration()),
      heartbeatInterval = heartbeatInterval,
      logLevel = logLevel,
      onStartup = startupTasks.filterIsInstance<RecurringTask<*>>(),
      logStackTrace = logStackTrace,
      pollingStrategy = PollingStrategyConfig.DEFAULT_SELECT_FOR_UPDATE,
      shutdownMaxWait = shutdownMaxWait,
      numberOfMissedHeartbeatsBeforeDead = numberOfMissedHeartbeatsBeforeDead,
      schedulerListeners = listeners
    ) {
      shutdownGracefully(schedulerScope, supervisorJob, executorService, houseKeeperExecutorService)
    }
  }

  private fun shutdownGracefully(
    schedulerScope: CoroutineScope?,
    supervisorJob: CompletableJob,
    executorService: ExecutorService?,
    houseKeeperExecutorService: ScheduledExecutorService?
  ) {
    schedulerScope?.cancel("Scheduler shutdown")

    // Give coroutines time to complete gracefully
    runBlocking {
      try {
        supervisorJob.children.forEach { it.join() }
      } catch (e: Exception) {
        LoggerFactory
          .getLogger(MongoScheduler::class.java)
          .warn("Some coroutines didn't complete gracefully during shutdown", e)
      }
    }

    executorService?.shutdown()
    houseKeeperExecutorService?.shutdown()

    try {
      if (executorService?.awaitTermination(30, TimeUnit.SECONDS) == false) {
        executorService.shutdownNow()
      }
      if (houseKeeperExecutorService?.awaitTermination(10, TimeUnit.SECONDS) == false) {
        houseKeeperExecutorService.shutdownNow()
      }
    } catch (_: InterruptedException) {
      executorService?.shutdownNow()
      houseKeeperExecutorService?.shutdownNow()
      Thread.currentThread().interrupt()
    }
  }

  private fun createCoroutineContext(
    supervisorJob: CompletableJob
  ): CoroutineContext = Dispatchers.IO + supervisorJob +
    CoroutineName("db-scheduler-mongo-$name") +
    CoroutineExceptionHandler { coroutineContext, throwable ->
      LoggerFactory
        .getLogger(MongoScheduler::class.java)
        .error("Database operation failed, context: {}", coroutineContext, throwable)
    }
}

@SchedulerDslMarker
fun scheduler(
  @SchedulerDslMarker block: MongoSchedulerDsl.() -> Unit
): Scheduler = MongoSchedulerDsl().apply(block).build()
