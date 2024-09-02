package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.ktor.receiveParametersTyped
import io.ktor.server.application.*
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.model.TaskDetailsRequestParams
import no.bekk.dbscheduler.ui.service.LogLogic

/**
 * Configuration for logs, requires history to be enabled
 * Will be opened later, still in development
 *
 * Documentation: `https://github.com/rocketbase-io/db-scheduler-log`
 */
internal fun Route.history(
  logLogic: LogLogic
) {
  get("logs") {
    val req = call.receiveParametersTyped<TaskDetailsRequestParams>()
    logLogic.getLogs(req)
  }

  get("poll") {
    val req = call.receiveParametersTyped<TaskDetailsRequestParams>()
    logLogic.pollLogs(req)
  }
}
