package io.github.osoykan.dbscheduler.ui.ktor.routing

import io.github.osoykan.dbscheduler.ui.ktor.DbSchedulerUIConfiguration
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.model.ConfigResponse

internal fun Route.config(config: DbSchedulerUIConfiguration) {
  get("config") {
    call.respond(ConfigResponse(config.logs.history))
  }
}
