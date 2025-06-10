@file:Suppress("unused")

package io.github.osoykan.scheduler

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.event.*
import com.github.kagkarlsson.scheduler.logging.LogLevel
import com.github.kagkarlsson.scheduler.serializer.*
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer.getDefaultObjectMapper
import com.github.kagkarlsson.scheduler.task.Task
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@DslMarker
annotation class SchedulerDslMarker

@SchedulerDslMarker
abstract class SchedulerDsl<T : DocumentDatabase<T>> {
  protected var database: T? = null
  protected var knownTasks: List<Task<*>> = emptyList()
  protected var startupTasks: List<Task<*>> = emptyList()
  protected var name: String = SchedulerName.Hostname().name
  protected var serializer: Serializer = JacksonSerializer(getDefaultObjectMapper().findAndRegisterModules().registerKotlinModule())
  protected var fixedThreadPoolSize: Int = 5
  protected var corePoolSize: Int = 1
  protected var heartbeatInterval: Duration = 2.seconds
  protected var executeDue: Duration = 2.seconds
  protected var deleteUnresolvedAfter: Duration = 1.seconds
  protected var logLevel: LogLevel = LogLevel.TRACE
  protected var logStackTrace: Boolean = true
  protected var shutdownMaxWait: Duration = 1.minutes
  protected var numberOfMissedHeartbeatsBeforeDead: Int = 3
  protected var listeners: List<SchedulerListener> = emptyList()
  protected var meterRegistry: MeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  protected var clock: Clock = UtcClock()

  fun database(database: T) {
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

  internal abstract fun build(): Scheduler
}
