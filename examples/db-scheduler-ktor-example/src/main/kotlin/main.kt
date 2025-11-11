import com.fasterxml.jackson.databind.ObjectMapper
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.*
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.zaxxer.hikari.*
import io.github.osoykan.scheduler.ui.ktor.DbSchedulerUI
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.*
import org.koin.ktor.ext.*
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import java.time.*
import javax.sql.DataSource

fun main() {
  val (hikariConfig, hikariDataSource) = postgresql()

  // Recurring task that executes frequently
  val recurringTask = Tasks
    .recurring("recurring-task", FixedDelay.ofSeconds(10))
    .execute { _, _ -> println("Hello, World! from recurring") }

  // Simple one-time task
  val oneTimeTask = Tasks
    .oneTime("one-time-task")
    .execute { _, _ -> println("hello from one time task!") }

  // Task with data payload for testing
  val dataTask = Tasks
    .oneTime("data-task")
    .execute { _, _ -> println("Task with data executed!") }

  // Fast recurring task for testing poll changes
  val fastTask = Tasks
    .recurring("fast-recurring", FixedDelay.ofSeconds(5))
    .execute { _, _ -> println("Fast recurring task executed") }

  // Slow recurring task
  val slowTask = Tasks
    .recurring("slow-recurring", FixedDelay.ofSeconds(30))
    .execute { _, _ -> println("Slow recurring task executed") }

  // Task that sometimes fails for testing failure states
  val flakyTask = Tasks
    .oneTime("flaky-task")
    .execute { _, _ ->
      if (Math.random() > 0.5) {
        println("Flaky task succeeded!")
      } else {
        throw RuntimeException("Flaky task failed randomly!")
      }
    }

  // Long running task to test picked state
  val longRunningTask = Tasks
    .oneTime("long-running-task")
    .execute { _, _ ->
      println("Long running task started...")
      Thread.sleep(15000) // 15 seconds
      println("Long running task completed!")
    }

  embeddedServer(Netty, port = 8080) {
    applicationEnvironment {
      log = LoggerFactory.getLogger("DbSchedulerKtorExample")
    }
    install(Koin) {
      registerJacksonSerialization()
      registerDbScheduler()
      modules(
        module {
          single { hikariConfig }
          single { hikariDataSource }.bind<DataSource>()
          single(named("oneTimeTask")) { oneTimeTask }.bind<Task<*>>()
          single(named("dataTask")) { dataTask }.bind<Task<*>>()
          single(named("flakyTask")) { flakyTask }.bind<Task<*>>()
          single(named("longRunningTask")) { longRunningTask }.bind<Task<*>>()
          single(named("recurringTask")) { recurringTask }.bind<RecurringTask<*>>()
          single(named("fastTask")) { fastTask }.bind<RecurringTask<*>>()
          single(named("slowTask")) { slowTask }.bind<RecurringTask<*>>()
        }
      )
    }
    configureContentNegotiation()
    configureDbScheduler()
    install(DbSchedulerUI) {
      routePath = "/db-scheduler"
      scheduler = { get() }
      enabled = true
    }

    monitor.subscribe(ApplicationStarted) {
      val scheduler = get<Scheduler>()
      val now = Instant.now()

      // Schedule immediate tasks
      scheduler.schedule(oneTimeTask.instance("immediate-task"), now)
      scheduler.schedule(dataTask.instance("data-1"), now.plus(Duration.ofSeconds(5)))
      scheduler.schedule(dataTask.instance("data-2"), now.plus(Duration.ofSeconds(10)))

      // Schedule tasks for near future to test polling
      scheduler.schedule(oneTimeTask.instance("future-task-1"), now.plus(Duration.ofSeconds(15)))
      scheduler.schedule(oneTimeTask.instance("future-task-2"), now.plus(Duration.ofSeconds(30)))
      scheduler.schedule(oneTimeTask.instance("future-task-3"), now.plus(Duration.ofSeconds(45)))

      // Schedule flaky tasks to test failure states
      scheduler.schedule(flakyTask.instance("flaky-1"), now.plus(Duration.ofSeconds(20)))
      scheduler.schedule(flakyTask.instance("flaky-2"), now.plus(Duration.ofSeconds(25)))
      scheduler.schedule(flakyTask.instance("flaky-3"), now.plus(Duration.ofSeconds(35)))

      // Schedule long running task to test picked state
      scheduler.schedule(longRunningTask.instance("long-1"), now.plus(Duration.ofSeconds(40)))

      // Schedule tasks with different execution times spread out
      scheduler.schedule(oneTimeTask.instance("batch-1"), now.plus(Duration.ofSeconds(60)))
      scheduler.schedule(oneTimeTask.instance("batch-2"), now.plus(Duration.ofSeconds(65)))
      scheduler.schedule(oneTimeTask.instance("batch-3"), now.plus(Duration.ofSeconds(70)))

      // Tasks for much later to have pending tasks
      scheduler.schedule(oneTimeTask.instance("later-1"), now.plus(Duration.ofSeconds(120)))
      scheduler.schedule(oneTimeTask.instance("later-2"), now.plus(Duration.ofSeconds(180)))

      println("=== Scheduled multiple tasks for testing polling endpoint ===")
      println("- Immediate tasks: immediate-task")
      println("- Near future tasks: future-task-1 (15s), future-task-2 (30s), future-task-3 (45s)")
      println("- Data tasks: data-1 (5s), data-2 (10s)")
      println("- Flaky tasks: flaky-1 (20s), flaky-2 (25s), flaky-3 (35s)")
      println("- Long running: long-1 (40s)")
      println("- Batch tasks: batch-1 (60s), batch-2 (65s), batch-3 (70s)")
      println("- Later tasks: later-1 (120s), later-2 (180s)")
      println("=== Test polling at: http://localhost:8080/db-scheduler-api/tasks/poll?filter=ALL ===")
    }

    routing {
      get("/") {
        call.respondRedirect { path("/db-scheduler") }
      }

      get("/add-task/{type}") {
        val scheduler = get<Scheduler>()
        val taskType = call.parameters["type"] ?: "one-time"
        val now = Instant.now()

        when (taskType) {
          "immediate" -> {
            val taskId = "manual-${System.currentTimeMillis()}"
            scheduler.schedule(oneTimeTask.instance(taskId), now)
            call.respond(mapOf("message" to "Scheduled immediate task: $taskId"))
          }
          "future" -> {
            val taskId = "future-${System.currentTimeMillis()}"
            scheduler.schedule(oneTimeTask.instance(taskId), now.plus(Duration.ofSeconds(10)))
            call.respond(mapOf("message" to "Scheduled future task: $taskId (10s from now)"))
          }
          "data" -> {
            val taskId = "data-${System.currentTimeMillis()}"
            scheduler.schedule(dataTask.instance(taskId), now.plus(Duration.ofSeconds(5)))
            call.respond(mapOf("message" to "Scheduled data task: $taskId (5s from now)"))
          }
          "flaky" -> {
            val taskId = "flaky-${System.currentTimeMillis()}"
            scheduler.schedule(flakyTask.instance(taskId), now.plus(Duration.ofSeconds(2)))
            call.respond(mapOf("message" to "Scheduled flaky task: $taskId (2s from now)"))
          }
          "long" -> {
            val taskId = "long-${System.currentTimeMillis()}"
            scheduler.schedule(longRunningTask.instance(taskId), now.plus(Duration.ofSeconds(2)))
            call.respond(mapOf("message" to "Scheduled long-running task: $taskId (2s from now)"))
          }
          else -> {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown task type. Use: immediate, future, data, flaky, long"))
          }
        }
      }

      get("/tasks-info") {
        call.respond(
          mapOf(
            "endpoints" to mapOf(
              "add-immediate" to "/add-task/immediate",
              "add-future" to "/add-task/future",
              "add-data" to "/add-task/data",
              "add-flaky" to "/add-task/flaky",
              "add-long-running" to "/add-task/long"
            ),
            "polling" to "/db-scheduler-api/tasks/poll?filter=ALL",
            "all-tasks" to "/db-scheduler-api/tasks/all?filter=ALL",
            "ui" to "/db-scheduler"
          )
        )
      }
    }
  }.start(wait = true)
}

private fun postgresql(): Pair<HikariConfig, HikariDataSource> {
  val postgresql = org.testcontainers.postgresql
    .PostgreSQLContainer("postgres:latest")
    .apply { start() }
  val hikariConfig = HikariConfig().apply {
    jdbcUrl = postgresql.jdbcUrl
    driverClassName = postgresql.driverClassName
    username = postgresql.username
    password = postgresql.password
    maximumPoolSize = 3
    addDataSourceProperty("dataSource.customizer.class", PostgreSqlJdbcCustomization::class.java.name)
  }
  val hikariDataSource = HikariDataSource(hikariConfig)
    .also { it.validate() }
    .also {
      val sql = getResourceAsText("dbScheduler.sql")
      require(sql.isNotBlank()) { "Failed to load dbScheduler.sql" }
      it.connection.use { connection ->
        connection.createStatement().use { statement ->
          statement.execute(sql)
        }
      }
    }
  return Pair(hikariConfig, hikariDataSource)
}

fun KoinApplication.registerJacksonSerialization() {
  modules(module { single { JacksonConfiguration.default } })
}

fun Application.configureContentNegotiation() {
  val mapper: ObjectMapper by inject()
  install(ContentNegotiation) {
    register(ContentType.Application.Json, JacksonConverter(mapper))
    register(ContentType.Application.ProblemJson, JacksonConverter(mapper))
  }
}
