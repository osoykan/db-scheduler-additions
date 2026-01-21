package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.backend.service.*
import io.github.osoykan.scheduler.ui.backend.util.Caching
import io.github.osoykan.scheduler.ui.ktor.DbSchedulerUIConfiguration
import io.ktor.server.routing.*

internal fun Routing.configureRouting(
  config: DbSchedulerUIConfiguration
) {
  /**
   * API for the db-scheduler
   * This is determined and used by the main library
   * https://github.com/bekk/db-scheduler-ui
   */
  val api = "db-scheduler-api"
  val caching = Caching<String, Any>(ttl = java.time.Duration.ofSeconds(60)) // Longer TTL for polling state
  val schedulerProvider = config.scheduler

  route(api) {
    config(config.historyEnabled)

    if (config.enabled) {
      val taskService = TaskService(schedulerProvider, caching, config.taskData)
      tasks(taskService)

      if (config.historyEnabled) {
        val logService = LogService(config.logRepository, caching)
        logs(logService)
      }
    }
  }
}
