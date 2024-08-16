package io.github.osoykan.dbscheduler.ui.ktor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.plugin.Koin

fun main() {
  embeddedServer(Netty, port = 8080) {
    install(Koin) {
      registerJacksonSerialization()
    }
    configureContentNegotiation()
    install(DbSchedulerUI) {
      routePath = "/db-scheduler"
    }

    routing {
      get("/") {
        call.respondText("Root Route")
      }
    }
  }.start(wait = true)
}
