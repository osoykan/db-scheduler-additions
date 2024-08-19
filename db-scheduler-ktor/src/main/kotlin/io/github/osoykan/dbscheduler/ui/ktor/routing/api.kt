package io.github.osoykan.dbscheduler.ui.ktor.routing

import io.github.osoykan.dbscheduler.ui.ktor.DbSchedulerUIConfiguration
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.service.*
import no.bekk.dbscheduler.ui.util.Caching

internal fun Routing.configureRouting(
  config: DbSchedulerUIConfiguration
) {
  /**
   * API for the db-scheduler
   * This is determined and used by the main library
   * https://github.com/bekk/db-scheduler-ui
   */
  val api = "db-scheduler-api"

  route(api) {
    config(config)
    if (config.enabled) {
      val caching = Caching()
      val dataSource = config.dataSource()
      val scheduler = config.scheduler()

      if (config.logs.history) {
        val logLogic = LogLogic(dataSource, config.serializer, caching, config.taskData, config.logs.logTableName, config.logs.logLimit)
        history(logLogic)
      }

      val taskLogic = TaskLogic(scheduler, caching, config.taskData)
      tasks(taskLogic)
    }
  }
}
