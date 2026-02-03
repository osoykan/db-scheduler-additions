package io.github.osoykan.scheduler.ui.ktor

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.event.SchedulerListener
import com.github.kagkarlsson.scheduler.serializer.Serializer
import io.github.osoykan.scheduler.ui.backend.listener.ExecutionLogListener
import io.github.osoykan.scheduler.ui.backend.repository.*
import io.github.osoykan.scheduler.ui.ktor.routing.configureRouting
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

/**
 * Installs DbSchedulerUI plugin with an existing [DbSchedulerUIConfiguration].
 * Use this when you need to share the config between scheduler and plugin:
 * ```
 * val uiConfig = DbSchedulerUIConfiguration().apply {
 *   historyEnabled = true
 * }
 *
 * // Add listener to scheduler
 * scheduler {
 *   listeners(uiConfig.createListener())
 * }
 *
 * // Install plugin with same config
 * install(DbSchedulerUI) {
 *   from(uiConfig)
 *   scheduler = { get() }
 * }
 * ```
 */
val DbSchedulerUI = createApplicationPlugin("DbSchedulerUI", createConfiguration = ::DbSchedulerUIConfiguration) {
  val config = pluginConfig

  application.routing {
    singlePageApplication {
      filesPath = "/static/db-scheduler"
      useResources = true
      applicationRoute = config.routePath
    }

    configureRouting(config)
  }
}

class DbSchedulerUIConfiguration {
  /**
   * Path to the UI, default is `/db-scheduler`
   */
  var routePath: String = "/db-scheduler"

  /**
   * Show task data in the UI, default is `true`
   */
  var taskData: Boolean = true

  /**
   * Scheduler instance
   */
  var scheduler: () -> Scheduler = { error("Scheduler not set") }

  /**
   * Serializer for the scheduler
   */
  var serializer: Serializer = Serializer.DEFAULT_JAVA_SERIALIZER

  /**
   * Enable the UI, default is `false`
   */
  var enabled: Boolean = false

  /**
   * Enable execution history tracking, default is `false`.
   * When enabled, task execution history is captured and displayed in the History tab.
   */
  var historyEnabled: Boolean = false

  /**
   * Maximum number of execution logs to retain in memory, default is `10000`.
   * Older entries are automatically removed when this limit is reached.
   */
  var historyMaxSize: Int = 10000

  /**
   * Log repository for storing execution history.
   * Defaults to [InMemoryLogRepository] with [historyMaxSize].
   */
  var logRepository: LogRepository = InMemoryLogRepository(historyMaxSize)

  /**
   * Creates the [ExecutionLogListener] that should be added to the scheduler.
   * Add this listener to your scheduler builder when [historyEnabled] is true:
   * ```
   * Scheduler.create(...)
   *   .addSchedulerListener(config.createListener())
   *   .build()
   * ```
   */
  fun createListener(): SchedulerListener = ExecutionLogListener(logRepository, taskData)

  /**
   * Copies settings from an existing [DbSchedulerUIConfiguration].
   * Use this to share the same config (and [logRepository]) between scheduler and plugin.
   */
  fun from(other: DbSchedulerUIConfiguration) {
    this.routePath = other.routePath
    this.taskData = other.taskData
    this.serializer = other.serializer
    this.enabled = other.enabled
    this.historyEnabled = other.historyEnabled
    this.historyMaxSize = other.historyMaxSize
    this.logRepository = other.logRepository
  }
}
