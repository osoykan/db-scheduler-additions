package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.ktor.DbSchedulerUIConfiguration
import io.ktor.server.response.*
import io.ktor.server.routing.*

internal fun Route.config(config: DbSchedulerUIConfiguration) {
  get("config") {
    call.respond(
      mapOf(
        "historyEnabled" to false,
        "configured" to true
      )
    ) // history disabled, polling enabled
  }
}
