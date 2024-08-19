package io.github.osoykan.dbscheduler.ui.ktor

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.serializer.Serializer
import io.github.osoykan.dbscheduler.ui.ktor.routing.configureRouting
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import javax.sql.DataSource

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

data class DbSchedulerUIConfiguration(
  /**
   * Path to the UI, default is `/db-scheduler`
   */
  var routePath: String = "/db-scheduler",

  /**
   * Show task data in the UI, default is `true`
   */
  var taskData: Boolean = true,

  /**
   * DataSource for the scheduler
   */
  var dataSource: () -> DataSource = { error("DataSource not set") },

  /**
   * Scheduler instance
   */
  var scheduler: () -> Scheduler = { error("Scheduler not set") },

  /**
   * Serializer for the scheduler
   */
  var serializer: Serializer = Serializer.DEFAULT_JAVA_SERIALIZER,

  /**
   * Enable the UI, default is `false`
   */
  var enabled: Boolean = false,
  /**
   * Configuration for logs, requires history to be enabled
   * Will be opened later, still in development
   *
   * Documentation: `https://github.com/rocketbase-io/db-scheduler-log`
   */
  internal var logs: LogConfiguration = LogConfiguration(),
) {
  data class LogConfiguration(
    val history: Boolean = false,
    val logTableName: String = "scheduled_execution_logs",
    val logLimit: Int = 0
  ) {
    init {
      if (history) {
        error("History is not yet implemented, please enable it later, and follow the documentation")
      }
    }
  }
}





