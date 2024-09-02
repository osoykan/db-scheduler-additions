package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.ktor.receiveParametersTyped
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.model.*
import no.bekk.dbscheduler.ui.service.TaskLogic
import java.time.Instant

internal fun Route.tasks(taskLogic: TaskLogic) {
  route("tasks") {
    get("all") {
      val params = call.receiveParametersTyped<TaskRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.getAllTasks(params))
    }

    get("details") {
      val params = call.receiveParametersTyped<TaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.getTask(params))
    }

    get("poll") {
      val params = call.receiveParametersTyped<TaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.pollTasks(params))
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
