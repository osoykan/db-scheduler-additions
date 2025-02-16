package io.github.osoykan.scheduler.ui.ktor.routing

import io.github.osoykan.scheduler.ui.ktor.DbSchedulerUIConfiguration
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.model.ConfigResponse

internal fun Route.config(config: DbSchedulerUIConfiguration) {
  get("config") {
    call.respond(ConfigResponse(config.logs.history, true))
  }
}
