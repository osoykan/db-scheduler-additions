package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.service.LogService
import io.github.osoykan.scheduler.ui.ktor.receiveParametersTyped
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Configuration for logs, requires history to be enabled
 * Will be opened later, still in development
 *
 * Documentation: `https://github.com/rocketbase-io/db-scheduler-log`
 */
internal fun Route.history(
  logLogic: LogService
) {
  get("logs/all") {
    val req = call.receiveParametersTyped<TaskDetailsRequestParams>()
    call.respond(logLogic.getLogs(req))
  }

  get("logs/poll") {
    val req = call.receiveParametersTyped<TaskDetailsRequestParams>()
    call.respond(logLogic.pollLogs(req))
  }
}
