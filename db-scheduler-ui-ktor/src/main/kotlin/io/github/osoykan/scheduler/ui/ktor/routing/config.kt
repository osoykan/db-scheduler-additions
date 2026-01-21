package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.ktor.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

internal fun Route.config(historyEnabled: Boolean = false) {
  get("config") {
    call.respond(mapOf("historyEnabled" to historyEnabled, "showHistory" to historyEnabled, "configured" to true).toJson())
  }
}
