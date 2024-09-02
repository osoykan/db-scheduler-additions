package io.github.osoykan.scheduler.mongo

import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.stats.*
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.mongodb.kotlin.client.coroutine.*
import io.github.osoykan.dbscheduler.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
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
  internal fun build(): Scheduler {
    requireNotNull(database) { "Database must be provided" }

    val statsRegistry = MicrometerStatsRegistry(meterRegistry, knownTasks + startupTasks)
    val taskResolver = TaskResolver(statsRegistry, clock, knownTasks + startupTasks)
    val executorService = Executors.newFixedThreadPool(fixedThreadPoolSize, NamedThreadFactory("db-scheduler-$name"))
    val houseKeeperExecutorService = Executors.newScheduledThreadPool(
      corePoolSize,
      NamedThreadFactory("db-scheduler-housekeeper-$name")
    )
    val dispatcher = executorService.asCoroutineDispatcher()
    val scope = CoroutineScope(
      dispatcher +
        SupervisorJob() +
        CoroutineName("db-scheduler-$name") +
        CoroutineExceptionHandler { coroutineContext, throwable ->
          LoggerFactory.getLogger(MongoScheduler::class.java)
            .error("Coroutine failed, context: {}", coroutineContext, throwable)
        }
    )
    val taskRepository = KTaskRepository(
      MongoTaskRepository(clock, database!!, taskResolver, SchedulerName.Fixed(name), serializer),
      scope
    ).also { it.createIndexes() }

    return MongoScheduler(
      clock = clock,
      schedulerTaskRepository = taskRepository,
      clientTaskRepository = taskRepository,
      taskResolver = taskResolver,
      schedulerName = SchedulerName.Fixed(name),
      threadPoolSize = corePoolSize,
      executorService = executorService,
      houseKeeperExecutorService = houseKeeperExecutorService,
      deleteUnresolvedAfter = deleteUnresolvedAfter,
      executeDueWaiter = Waiter(executeDue.toJavaDuration()),
      heartbeatInterval = heartbeatInterval,
      logLevel = logLevel,
      onStartup = startupTasks.filterIsInstance<RecurringTask<*>>(),
      logStackTrace = logStackTrace,
      pollingStrategy = PollingStrategyConfig.DEFAULT_SELECT_FOR_UPDATE,
      shutdownMaxWait = shutdownMaxWait,
      numberOfMissedHeartbeatsBeforeDead = numberOfMissedHeartbeatsBeforeDead,
      schedulerListeners = listeners + listOf(StatsRegistryAdapter(statsRegistry))
    ) {
      scope.cancel()
      dispatcher.cancel()
    }
  }
}

@SchedulerDslMarker
fun scheduler(
  @SchedulerDslMarker block: MongoSchedulerDsl.() -> Unit
): Scheduler = MongoSchedulerDsl().apply(block).build()
