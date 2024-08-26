package com.github.kagkarlsson.scheduler.couchbase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.Clock
import com.github.kagkarlsson.scheduler.event.*
import com.github.kagkarlsson.scheduler.logging.LogLevel
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer
import com.github.kagkarlsson.scheduler.stats.*
import com.github.kagkarlsson.scheduler.task.*
import io.micrometer.core.instrument.Metrics
import io.micrometer.prometheusmetrics.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.*
import java.util.concurrent.*
import kotlin.time.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("LongParameterList")
class CouchbaseScheduler(
  clock: Clock,
  schedulerTaskRepository: TaskRepository,
  clientTaskRepository: TaskRepository,
  taskResolver: TaskResolver,
  threadPoolSize: Int,
  executorService: ExecutorService,
  houseKeeperExecutorService: ScheduledExecutorService,
  schedulerName: SchedulerName,
  executeDueWaiter: Waiter,
  heartbeatInterval: Duration,
  numberOfMissedHeartbeatsBeforeDead: Int,
  pollingStrategy: PollingStrategyConfig,
  deleteUnresolvedAfter: Duration,
  shutdownMaxWait: Duration,
  onStartup: List<OnStartup>,
  schedulerListeners: List<SchedulerListener> = listOf(),
  interceptors: List<ExecutionInterceptor> = listOf(),
  logLevel: LogLevel = LogLevel.INFO,
  logStackTrace: Boolean = false,
  private val onStop: () -> Unit = {}
) : Scheduler(
  clock,
  schedulerTaskRepository,
  clientTaskRepository,
  taskResolver,
  threadPoolSize,
  executorService,
  schedulerName,
  executeDueWaiter,
  heartbeatInterval.toJavaDuration(),
  numberOfMissedHeartbeatsBeforeDead,
  schedulerListeners.toMutableList(),
  interceptors.toMutableList(),
  pollingStrategy,
  deleteUnresolvedAfter.toJavaDuration(),
  shutdownMaxWait.toJavaDuration(),
  logLevel,
  logStackTrace,
  onStartup,
  executorService,
  houseKeeperExecutorService
) {
  override fun stop() {
    super.stop()
    onStop()
  }

  object AppMicrometer {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
      .also { Metrics.addRegistry(it) }
  }

  companion object {
    val defaultObjectMapper: ObjectMapper = jacksonObjectMapper().apply { findAndRegisterModules() }

    class UtcClock : Clock {
      override fun now(): Instant = Instant.now().atZone(ZoneOffset.UTC).toInstant()
    }

    private class NamedThreadFactory(private val name: String) : ThreadFactory {
      private val threadFactory = Executors.defaultThreadFactory()

      override fun newThread(r: Runnable): Thread {
        val thread = threadFactory.newThread(r)
        thread.name = name + "-" + thread.name
        return thread
      }
    }

    fun create(
      couchbase: Couchbase,
      knownTasks: List<Task<*>> = emptyList(),
      name: String = SchedulerName.Hostname().name,
      objectMapper: ObjectMapper = defaultObjectMapper,
      fixedThreadPoolSize: Int = 5,
      corePoolSize: Int = 1
    ): Scheduler {
      val logger = LoggerFactory.getLogger(CouchbaseScheduler::class.java)
      val clock = UtcClock()
      val statsRegistry = MicrometerStatsRegistry(AppMicrometer.registry, knownTasks)
      val taskResolver = TaskResolver(statsRegistry, clock, knownTasks)
      val serializer = JacksonSerializer(objectMapper)
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
            logger.error("Coroutine failed, context: {}", coroutineContext, throwable)
          }
      )
      val taskRepository = DecoratedCouchbaseTaskRepository(
        SuspendedCouchbaseTaskRepository(clock, couchbase, taskResolver, SchedulerName.Fixed(name), serializer),
        scope
      )
      return CouchbaseScheduler(
        clock = clock,
        schedulerTaskRepository = taskRepository,
        clientTaskRepository = taskRepository,
        taskResolver = taskResolver,
        schedulerName = SchedulerName.Fixed(name),
        threadPoolSize = corePoolSize,
        executorService = executorService,
        houseKeeperExecutorService = houseKeeperExecutorService,
        deleteUnresolvedAfter = 1.seconds,
        executeDueWaiter = Waiter(2.seconds.toJavaDuration()),
        heartbeatInterval = 2.seconds,
        logLevel = LogLevel.TRACE,
        onStartup = emptyList(),
        logStackTrace = true,
        pollingStrategy = PollingStrategyConfig.DEFAULT_SELECT_FOR_UPDATE,
        shutdownMaxWait = 1.minutes,
        numberOfMissedHeartbeatsBeforeDead = 3,
        schedulerListeners = listOf(StatsRegistryAdapter(statsRegistry)),
      ) {
        scope.cancel()
        dispatcher.cancel()
      }
    }
  }
}
