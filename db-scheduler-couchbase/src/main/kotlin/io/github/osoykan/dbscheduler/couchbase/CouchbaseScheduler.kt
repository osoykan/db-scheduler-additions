package io.github.osoykan.dbscheduler.couchbase

import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.event.*
import com.github.kagkarlsson.scheduler.logging.LogLevel
import com.github.kagkarlsson.scheduler.task.OnStartup
import java.util.concurrent.*
import kotlin.time.*

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
}
