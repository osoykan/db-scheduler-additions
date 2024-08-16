package io.github.osoykan.dbscheduler.ui.ktor

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.serializer.Serializer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.bekk.dbscheduler.ui.model.*
import no.bekk.dbscheduler.ui.service.*
import no.bekk.dbscheduler.ui.util.Caching
import java.time.Instant
import javax.sql.DataSource

data class DbSchedulerUIConfiguration(
  var routePath: String = "/db-scheduler",
  var taskData: Boolean = true,
  var dataSource: DataSource? = null,
  var scheduler: Scheduler? = null,
  var serializer: Serializer = Serializer.DEFAULT_JAVA_SERIALIZER,
  var logs: LogConfiguration = LogConfiguration(),
  var enabled: Boolean = false
) {
  data class LogConfiguration(
    val history: Boolean = false,
    val logTableName: String = "scheduled_execution_logs",
    val logLimit: Int = 0
  )
}

val DbSchedulerUI = createApplicationPlugin("DbSchedulerUI", createConfiguration = ::DbSchedulerUIConfiguration) {
  val config = pluginConfig
  application.routing {
    singlePageApplication {
      filesPath = "/static/db-scheduler"
      useResources = true
      applicationRoute = config.routePath
    }

    configureRouting(config)
  }
}

private fun Routing.configureRouting(
  config: DbSchedulerUIConfiguration
) {
  val api = "db-scheduler-api"
  route(api) {
    config(config)
    if (config.enabled) {
      val caching = Caching()
      val dataSource = config.dataSource!!
      val scheduler = config.scheduler!!
      if (config.logs.history) {
        history(dataSource, config, caching)
      }

      val taskLogic = TaskLogic(scheduler, caching, config.taskData)
      tasks(taskLogic)
    }
  }
}

private fun Route.tasks(taskLogic: TaskLogic) {
  route("tasks") {
    get("all") {
      val params = call.receive<TaskRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.getAllTasks(params))
    }

    get("details") {
      val params = call.receive<TaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.getTask(params))
    }

    get("poll") {
      val params = call.receive<TaskDetailsRequestParams>()
      call.respond(HttpStatusCode.OK, taskLogic.pollTasks(params))
    }

    post("rerun") {
      val id = call.request.queryParameters["id"] ?: throw IllegalArgumentException("Task id is required")
      val name = call.request.queryParameters["name"] ?: throw IllegalArgumentException("Task name is required")
      val scheduleTime = call.request.queryParameters["scheduleTime"] ?: throw IllegalArgumentException("Instant is required")
      val scheduleTimeInstant = Instant.parse(scheduleTime)
      call.respond(HttpStatusCode.OK, taskLogic.runTaskNow(id, name, scheduleTimeInstant))
    }

    post("rerunGroup") {
      val groupName = call.request.queryParameters["name"] ?: throw IllegalArgumentException("Group name is required")
      val onlyFailed = call.request.queryParameters["onlyFailed"] ?: throw IllegalArgumentException("Only failed is required")
      call.respond(HttpStatusCode.OK, taskLogic.runTaskGroupNow(groupName, onlyFailed.toBoolean()))
    }

    post("delete") {
      val id = call.request.queryParameters["id"] ?: throw IllegalArgumentException("Task id is required")
      val name = call.request.queryParameters["name"] ?: throw IllegalArgumentException("Task name is required")
      call.respond(HttpStatusCode.OK, taskLogic.deleteTask(id, name))
    }
  }
}

private fun Route.history(
  dataSource: DataSource,
  config: DbSchedulerUIConfiguration,
  caching: Caching
) {
  val logLogic = LogLogic(dataSource, config.serializer, caching, config.taskData, config.logs.logTableName, config.logs.logLimit)
  get("logs") {
    val req = call.receive<TaskDetailsRequestParams>()
    logLogic.getLogs(req)
  }

  get("poll") {
    val req = call.receive<TaskDetailsRequestParams>()
    logLogic.pollLogs(req)
  }
}

private fun Route.config(config: DbSchedulerUIConfiguration) {
  get("config") {
    call.respond(ConfigResponse(config.logs.history))
  }
}
