import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.*
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.zaxxer.hikari.*
import io.github.osoykan.scheduler.ui.ktor.DbSchedulerUIConfiguration
import io.github.osoykan.scheduler.ui.ktor.dbSchedulerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.qualifier.named
import org.koin.dsl.*
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import java.time.*
import javax.sql.DataSource

fun main() {
  val (hikariConfig, hikariDataSource) = postgresql()

  // Recurring task that executes frequently
  val recurringTask = Tasks
    .recurring("recurring-task", FixedDelay.ofSeconds(10))
    .execute { _, _ -> println("Hello, World! from authenticated recurring") }

  // Simple one-time task
  val oneTimeTask = Tasks
    .oneTime("one-time-task")
    .execute { _, _ -> println("hello from one time task (auth)!") }

  embeddedServer(Netty, port = 8081) {
    applicationEnvironment {
      log = LoggerFactory.getLogger("DbSchedulerKtorAuthenticatedExample")
    }

    // Create UI config first so we can share the listener with the scheduler
    val uiConfig = DbSchedulerUIConfiguration().apply {
      routePath = "/db-scheduler"
      enabled = true
      historyEnabled = true
      historyMaxSize = 10_000
    }

    // Install Koin for dependency injection
    install(Koin) {
      registerDbScheduler()
      modules(
        module {
          single { hikariConfig }
          single { hikariDataSource }.bind<DataSource>()
          single(named("oneTimeTask")) { oneTimeTask }.bind<Task<*>>()
          single(named("recurringTask")) { recurringTask }.bind<RecurringTask<*>>()
          single { uiConfig.createListener() }
        }
      )
    }

    configureContentNegotiation()
    configureDbScheduler()

    // 1. Install Ktor's standard Authentication plugin
    install(Authentication) {
      basic("db-scheduler-auth") {
        realm = "Access to db-scheduler UI"
        validate { credentials ->
          if (credentials.name == "admin" && credentials.password == "admin") {
            UserIdPrincipal(credentials.name)
          } else {
            null
          }
        }
      }
    }

    // 2. Wrap the db-scheduler UI and API routes under the authenticated block
    routing {
      get("/") {
        call.respondRedirect { path("/db-scheduler") }
      }

      authenticate("db-scheduler-auth") {
        // Expose db-scheduler UI routes inside this authenticated block!
        // This is possible because we extracted dbSchedulerUI as a Route extension function.
        dbSchedulerUI(DbSchedulerUIConfiguration().apply {
          from(uiConfig)
          scheduler = { get() }
        })
      }
    }

    monitor.subscribe(ApplicationStarted) {
      val scheduler = get<Scheduler>()
      val now = Instant.now()

      // Schedule some initial tasks
      scheduler.schedule(oneTimeTask.instance("authenticated-task-1"), now)
      scheduler.schedule(oneTimeTask.instance("authenticated-task-2"), now.plus(Duration.ofSeconds(15)))

      println("=========================================================================")
      println(" Authenticated DbScheduler UI Ktor Example is running!")
      println(" URL: http://localhost:8081/db-scheduler")
      println(" Credentials: admin / admin")
      println("=========================================================================")
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

fun Application.configureContentNegotiation() {
  install(ContentNegotiation) {
    val format = kotlinx.serialization.json.Json {}
    register(ContentType.Application.Json, KotlinxSerializationConverter(format))
    register(ContentType.Application.ProblemJson, KotlinxSerializationConverter(format))
  }
}
