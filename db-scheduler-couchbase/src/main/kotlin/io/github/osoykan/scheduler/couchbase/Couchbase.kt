package io.github.osoykan.scheduler.couchbase

import arrow.core.raise.option
import com.couchbase.client.core.io.CollectionIdentifier
import com.couchbase.client.kotlin.*
import com.couchbase.client.kotlin.Collection
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Waiter
import com.github.kagkarlsson.scheduler.stats.*
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import io.github.osoykan.scheduler.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import kotlin.time.toJavaDuration

data class Couchbase(
  val cluster: Cluster,
  val bucketName: String,
  override val collection: String = CollectionIdentifier.DEFAULT_COLLECTION
) : DocumentDatabase<Couchbase> {
  private val logger = LoggerFactory.getLogger(Couchbase::class.java)
  private val bucket = cluster.bucket(bucketName)
  private val defaultScope = bucket.defaultScope()
  val schedulerCollection: Collection by lazy { cluster.bucket(bucketName).collection(collection) }

  override suspend fun ensureCollectionExists() {
    option {
      val exists = bucket.collections
        .getScope(defaultScope.name)
        .collections
        .any { it.name == collection }
      if (exists) {
        logger.debug("Collection $collection already exists")
        return@option
      }

      logger.debug("Creating collection $collection")
      cluster.bucket(bucketName).collections.createCollection(defaultScope.name, collection)
      logger.debug("Collection $collection created")
    }
  }

  override fun withCollection(collection: String): Couchbase = copy(collection = collection)
}

@SchedulerDslMarker
class CouchbaseSchedulerDsl : SchedulerDsl<Couchbase>() {
  private var executorService: ExecutorService? = null
  private var houseKeeperExecutorService: ScheduledExecutorService? = null
  private var schedulerScope: CoroutineScope? = null

  internal fun build(): Scheduler {
    requireNotNull(database) { "Database must be provided" }

    val statsRegistry = MicrometerStatsRegistry(meterRegistry, knownTasks + startupTasks)
    val taskResolver = TaskResolver(statsRegistry, clock, knownTasks + startupTasks)

    executorService = Executors.newFixedThreadPool(
      fixedThreadPoolSize,
      NamedThreadFactory("db-scheduler-$name")
    )

    houseKeeperExecutorService = Executors.newScheduledThreadPool(
      corePoolSize,
      NamedThreadFactory("house-keeper-executor-$name")
    )

    val dispatcher = executorService!!.asCoroutineDispatcher()
    schedulerScope = CoroutineScope(
      dispatcher +
        SupervisorJob() +
        CoroutineName("db-scheduler-$name") +
        CoroutineExceptionHandler { coroutineContext, throwable ->
          val logger = LoggerFactory.getLogger(SchedulerDsl::class.java)
          logger.error("Coroutine failed, context: {}", coroutineContext, throwable)
        }
    )

    val taskRepository = KTaskRepository(
      CouchbaseTaskRepository(clock, database!!, taskResolver, SchedulerName.Fixed(name), serializer),
      schedulerScope!!,
      clock
    ).also { it.createIndexes() }

    return CouchbaseScheduler(
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
      schedulerListeners = listeners + listOf(StatsRegistryAdapter(statsRegistry))
    ) {
      schedulerScope?.cancel("Scheduler shutdown")
      executorService?.shutdown()
      houseKeeperExecutorService?.shutdown()

      try {
        if (executorService?.awaitTermination(30, TimeUnit.SECONDS) == false) {
          executorService?.shutdownNow()
        }
        if (houseKeeperExecutorService?.awaitTermination(10, TimeUnit.SECONDS) == false) {
          houseKeeperExecutorService?.shutdownNow()
        }
      } catch (_: InterruptedException) {
        executorService?.shutdownNow()
        houseKeeperExecutorService?.shutdownNow()
        Thread.currentThread().interrupt()
      }
    }
  }
}

@SchedulerDslMarker
fun scheduler(
  @SchedulerDslMarker block: CouchbaseSchedulerDsl.() -> Unit
): Scheduler = CouchbaseSchedulerDsl().apply(block).build()
