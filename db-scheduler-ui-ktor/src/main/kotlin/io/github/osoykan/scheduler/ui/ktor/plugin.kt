package io.github.osoykan.scheduler.ui.ktor

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.serializer.Serializer
import io.github.osoykan.scheduler.ui.backend.listener.ExecutionLogListener
import io.github.osoykan.scheduler.ui.backend.repository.LogRepository
import io.github.osoykan.scheduler.ui.ktor.routing.configureRouting
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

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
   * Internal log repository for storing execution history.
   */
  internal var logRepository: LogRepository = LogRepository(historyMaxSize)
    private set

  /**
   * Internal flag to check if the listener has been created.
   */
  private var listenerCreated: Boolean = false

  /**
   * Creates and returns a SchedulerListener that captures execution history.
   * This listener should be added to your Scheduler configuration.
   *
   * IMPORTANT: Call this method ONCE and use the returned listener in both:
   * 1. Your Scheduler configuration (.addSchedulerListener)
   * 2. The same instance will be used by the UI plugin automatically
   *
   * Example:
   * ```kotlin
   * // Create the UI configuration
   * val uiConfig = DbSchedulerUIConfiguration()
   * uiConfig.historyEnabled = true
   *
   * // Get the history listener - call this only once
   * val historyListener = uiConfig.createHistoryListener()
   *
   * // Use in scheduler
   * Scheduler.create(dataSource, tasks)
   *   .addSchedulerListener(historyListener)
   *   .build()
   *
   * // Install UI plugin with same configuration
   * install(DbSchedulerUI) {
   *   routePath = uiConfig.routePath
   *   scheduler = { scheduler }
   *   enabled = uiConfig.enabled
   *   historyEnabled = uiConfig.historyEnabled
   *   // The logRepository is shared automatically
   *   useHistoryListenerFrom(uiConfig)
   * }
   * ```
   *
   * @param captureTaskData Whether to capture task data in the logs
   * @return ExecutionLogListener to be added to the scheduler
   */
  fun createHistoryListener(captureTaskData: Boolean = taskData): ExecutionLogListener {
    if (!listenerCreated) {
      logRepository = LogRepository(historyMaxSize)
      listenerCreated = true
    }
    return ExecutionLogListener(logRepository, captureTaskData)
  }

  /**
   * Use the history listener and log repository from another configuration.
   * This allows sharing the log repository between the scheduler and the UI plugin.
   *
   * @param otherConfig The configuration that created the history listener
   */
  fun useHistoryListenerFrom(otherConfig: DbSchedulerUIConfiguration) {
    this.logRepository = otherConfig.logRepository
    this.historyEnabled = otherConfig.historyEnabled
  }
}
