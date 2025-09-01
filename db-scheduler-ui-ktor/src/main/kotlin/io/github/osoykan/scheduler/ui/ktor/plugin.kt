package io.github.osoykan.scheduler.ui.ktor

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.serializer.Serializer
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
  var enabled: Boolean = false
)
