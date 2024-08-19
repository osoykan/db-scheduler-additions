import com.fasterxml.jackson.databind.ObjectMapper
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.*
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.zaxxer.hikari.*
import io.github.osoykan.dbscheduler.ui.ktor.DbSchedulerUI
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.KoinApplication
import org.koin.dsl.*
import org.koin.ktor.ext.*
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource

fun main() {
  val (hikariConfig, hikariDataSource) = postgresql()
  val recurringTask = Tasks.recurring("recurring-task", FixedDelay.ofSeconds(10))
    .execute { _, _ -> println("Hello, World! from recurring") }
  val task = Tasks.oneTime("one-time-task")
    .execute { _, _ -> println("hello from one time task!") }

  embeddedServer(Netty, port = 8080) {
    applicationEngineEnvironment {
      log = LoggerFactory.getLogger("DbSchedulerKtorExample")
    }
    install(Koin) {
      registerJacksonSerialization()
      registerDbScheduler()
      modules(
        module {
          single { hikariConfig }
          single { hikariDataSource }.bind<DataSource>()
          single { task }.bind<Task<*>>()
          single { recurringTask }.bind<RecurringTask<*>>()
        }
      )
    }
    configureContentNegotiation()
    configureDbScheduler()
    install(DbSchedulerUI) {
      routePath = "/db-scheduler"
      scheduler = { get() }
      dataSource = { get() }
      enabled = true
    }
    environment.monitor.subscribe(ApplicationStarted) {
      val scheduler = get<Scheduler>()
      scheduler.schedule(task.instance("new-task"), Instant.now())
    }

    routing {
      get("/") {
        call.respondRedirect { path("/db-scheduler") }
      }
    }
  }.start(wait = true)
}

private fun postgresql(): Pair<HikariConfig, HikariDataSource> {
  val postgresql = PostgreSQLContainer("postgres:latest")
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
