package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.backend.model.LogRequestParams
import io.github.osoykan.scheduler.ui.backend.service.LogService
import io.github.osoykan.scheduler.ui.ktor.receiveParametersTyped
import io.github.osoykan.scheduler.ui.ktor.toJson
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

internal fun Route.logs(logService: LogService) {
  route("logs") {
    get("all") {
      val params = call.receiveParametersTyped<LogRequestParams>()
      call.respond(HttpStatusCode.OK, logService.getAllLogs(params).toJson())
    }

    get("poll") {
      val params = call.receiveParametersTyped<LogRequestParams>()
      call.respond(HttpStatusCode.OK, logService.pollLogs(params).toJson())
    }
  }
}
