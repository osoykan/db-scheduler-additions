package io.github.osoykan.dbscheduler.couchbase

import arrow.core.raise.option
import arrow.core.toOption
import com.couchbase.client.core.io.CollectionIdentifier
import com.couchbase.client.kotlin.*
import com.couchbase.client.kotlin.Collection
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Waiter
import com.github.kagkarlsson.scheduler.event.SchedulerListener
import com.github.kagkarlsson.scheduler.logging.LogLevel
import com.github.kagkarlsson.scheduler.serializer.*
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer.getDefaultObjectMapper
import com.github.kagkarlsson.scheduler.stats.*
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import io.github.osoykan.dbscheduler.common.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import kotlin.time.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class Couchbase(
  val cluster: Cluster,
  val bucketName: String,
  override val collection: String = CollectionIdentifier.DEFAULT_COLLECTION
) : DocumentDatabase<Couchbase> {
  private val logger = LoggerFactory.getLogger(Couchbase::class.java)
  private val bucket = cluster.bucket(bucketName)
  private val defaultScope = bucket.defaultScope()
  val schedulerCollection: Collection by lazy { collection.let { cluster.bucket(bucketName).collection(it) } }

  override suspend fun ensureCollectionExists() {
    option {
      val collection = collection.toOption().bind()
      val exists = bucket.collections.getScope(defaultScope.name).collections.any { it.name == collection }
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

class CouchbaseSchedulerDsl {
  private var database: Couchbase? = null
  private var knownTasks: List<Task<*>> = emptyList()
  private var startupTasks: List<Task<*>> = emptyList()
  private var name: String = SchedulerName.Hostname().name
  private var serializer: Serializer = JacksonSerializer(getDefaultObjectMapper().findAndRegisterModules().registerKotlinModule())
  private var fixedThreadPoolSize: Int = 5
  private var corePoolSize: Int = 1
  private var heartbeatInterval: Duration = 2.seconds
  private var executeDue: Duration = 2.seconds
  private var deleteUnresolvedAfter: Duration = 1.seconds
  private var logLevel: LogLevel = LogLevel.TRACE
  private var logStackTrace: Boolean = true
  private var shutdownMaxWait: Duration = 1.minutes
  private var numberOfMissedHeartbeatsBeforeDead: Int = 3
  private var listeners: List<SchedulerListener> = emptyList()
  private var meterRegistry: MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  private var clock: Clock = UtcClock()

  fun database(database: Couchbase) {
    this.database = database
  }

  fun knownTasks(vararg tasks: Task<*>) {
    this.knownTasks = tasks.toList()
  }

  fun startupTasks(vararg tasks: Task<*>) {
    this.startupTasks = tasks.toList()
  }

  fun name(name: String) {
    this.name = name
  }

  fun serializer(serializer: Serializer) {
    this.serializer = serializer
  }

  fun fixedThreadPoolSize(size: Int) {
    this.fixedThreadPoolSize = size
  }

  fun corePoolSize(size: Int) {
    this.corePoolSize = size
  }

  fun heartbeatInterval(duration: Duration) {
    this.heartbeatInterval = duration
  }

  fun executeDue(duration: Duration) {
    this.executeDue = duration
  }

  fun deleteUnresolvedAfter(duration: Duration) {
    this.deleteUnresolvedAfter = duration
  }

  fun logLevel(level: LogLevel) {
    this.logLevel = level
  }

  fun logStackTrace(enabled: Boolean) {
    this.logStackTrace = enabled
  }

  fun shutdownMaxWait(duration: Duration) {
    this.shutdownMaxWait = duration
  }

  fun numberOfMissedHeartbeatsBeforeDead(count: Int) {
    this.numberOfMissedHeartbeatsBeforeDead = count
  }

  fun listeners(vararg listeners: SchedulerListener) {
    this.listeners = listeners.toList()
  }

  fun meterRegistry(meterRegistry: MeterRegistry) {
    this.meterRegistry = meterRegistry
  }

  fun clock(clock: Clock) {
    this.clock = clock
  }

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
          LoggerFactory.getLogger(CouchbaseScheduler::class.java)
            .error("Coroutine failed, context: {}", coroutineContext, throwable)
        }
    )
    val taskRepository = KTaskRepository(
      CouchbaseTaskRepository(clock, database!!, taskResolver, SchedulerName.Fixed(name), serializer),
      scope
    ).also { it.createIndexes() }

    return CouchbaseScheduler(
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

fun scheduler(block: CouchbaseSchedulerDsl.() -> Unit): Scheduler = CouchbaseSchedulerDsl().apply(block).build()
