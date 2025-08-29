package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams as OurTaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.model.TaskRequestParams as OurTaskRequestParams
import io.github.osoykan.scheduler.ui.ktor.receiveParametersTyped
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.model.TaskDetailsRequestParams
import no.bekk.dbscheduler.ui.model.TaskRequestParams
import no.bekk.dbscheduler.ui.service.TaskLogic
import java.time.Instant

internal fun Route.tasks(taskLogic: TaskLogic) {
  route("tasks") {
    get("all") {
      val params = call.receiveParametersTyped<OurTaskRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.getAllTasks(params.toBekk()))
    }

    get("details") {
      val params = call.receiveParametersTyped<OurTaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.getTask(params.toBekk()))
    }

    get("poll") {
      val params = call.receiveParametersTyped<OurTaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.pollTasks(params.toBekk()))
    }

    post("rerun") {
      val id = requireNotNull(call.request.queryParameters["id"]) { "Task id is required" }
      val name = requireNotNull(call.request.queryParameters["name"]) { "Task name is required" }
      val scheduleTime = requireNotNull(call.request.queryParameters["scheduleTime"]) { "Schedule time is required" }
      val scheduleTimeInstant = Instant.parse(scheduleTime)
      call.respond(HttpStatusCode.OK, taskLogic.runTaskNow(id, name, scheduleTimeInstant))
    }

    post("rerunGroup") {
      val groupName = requireNotNull(call.request.queryParameters["groupName"]) { "Group name is required" }
      val onlyFailed = requireNotNull(call.request.queryParameters["onlyFailed"]) { "Only failed is required" }
      call.respond(HttpStatusCode.OK, taskLogic.runTaskGroupNow(groupName, onlyFailed.toBoolean()))
    }

    post("delete") {
      val id = requireNotNull(call.request.queryParameters["id"]) { "Task id is required" }
      val name = requireNotNull(call.request.queryParameters["name"]) { "Task name is required" }
      call.respond(HttpStatusCode.OK, taskLogic.deleteTask(id, name))
    }
  }
}

private fun OurTaskRequestParams.toBekk(): TaskRequestParams =
  TaskRequestParams(
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
    isRefresh
  )

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
