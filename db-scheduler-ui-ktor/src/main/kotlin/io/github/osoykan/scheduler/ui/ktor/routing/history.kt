package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams as OurTaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.ktor.receiveParametersTyped
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.model.TaskDetailsRequestParams
import no.bekk.dbscheduler.ui.model.TaskRequestParams
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
    val req = call.receiveParametersTyped<OurTaskDetailsRequestParams>()
    logLogic.getLogs(req.toBekk())
  }

  get("poll") {
    val req = call.receiveParametersTyped<OurTaskDetailsRequestParams>()
    logLogic.pollLogs(req.toBekk())
  }
}

private fun OurTaskDetailsRequestParams.toBekk(): TaskDetailsRequestParams =
  TaskDetailsRequestParams(
    TaskRequestParams.TaskFilter.valueOf(filter.name),
    pageNumber,
    size,
    TaskRequestParams.TaskSort.valueOf(sorting.name),
    isAsc,
    searchTermTaskName,
    searchTermTaskInstance,
    isTaskNameExactMatch,
    isTaskInstanceExactMatch,
    startTime,
    endTime,
    taskName,
    taskId,
    isRefresh
  )
