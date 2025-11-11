package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.model.TaskRequestParams
import io.github.osoykan.scheduler.ui.backend.service.TaskService
import io.github.osoykan.scheduler.ui.ktor.receiveParametersTyped
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

internal fun Route.tasks(taskService: TaskService) {
  route("tasks") {
    get("all") {
      val params = call.receiveParametersTyped<TaskRequestParams>()
      call.respond(HttpStatusCode.OK, taskService.getAllTasks(params))
    }

    get("details") {
      val params = call.receiveParametersTyped<TaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskService.getTask(params))
    }

    get("poll") {
      val params = call.receiveParametersTyped<TaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskService.pollTasks(params))
    }

    post("rerun") {
      val id = requireNotNull(call.request.queryParameters["id"]) { "Task id is required" }
      val name = requireNotNull(call.request.queryParameters["name"]) { "Task name is required" }
      val scheduleTime = requireNotNull(call.request.queryParameters["scheduleTime"]) { "Schedule time is required" }
      val scheduleTimeInstant = Instant.parse(scheduleTime)
      call.respond(HttpStatusCode.OK, taskService.runTaskNow(id, name, scheduleTimeInstant))
    }

    post("rerunGroup") {
      val groupName = requireNotNull(call.request.queryParameters["name"]) { "Group name is required" }
      val onlyFailed = requireNotNull(call.request.queryParameters["onlyFailed"]) { "Only failed is required" }
      call.respond(HttpStatusCode.OK, taskService.runTaskGroupNow(groupName, onlyFailed.toBoolean()))
    }

    post("delete") {
      val id = requireNotNull(call.request.queryParameters["id"]) { "Task id is required" }
      val name = requireNotNull(call.request.queryParameters["name"]) { "Task name is required" }
      call.respond(HttpStatusCode.OK, taskService.deleteTask(id, name))
    }
  }
}
