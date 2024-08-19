import arrow.core.Either
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.logging.LogLevel
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.zaxxer.hikari.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import org.koin.core.KoinApplication
import org.koin.dsl.*
import org.koin.ktor.ext.get
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

fun KoinApplication.registerDbScheduler() {
  modules(
    module {
      single { dbScheduler(get(), get(), getAll(), getAll()) }.bind<Scheduler>().bind<SchedulerClient>()
    }
  )
}

private fun dbScheduler(
  postgresConfig: HikariConfig,
  objectMapper: ObjectMapper,
  tasks: List<Task<*>>,
  recurringTasks: List<RecurringTask<*>>
): Scheduler {
  val dataSource = dataSource(postgresConfig)
  return Scheduler.create(dataSource, tasks)
    .schedulerName(SchedulerName.Hostname())
    .alwaysPersistTimestampInUTC()
    .startTasks(recurringTasks)
    .serializer(JacksonSerializer(objectMapper))
    .deleteUnresolvedAfter(10.seconds.toJavaDuration())
    .shutdownMaxWait(5.seconds.toJavaDuration())
    .failureLogging(LogLevel.WARN, true)
    .threads(10)
    .registerShutdownHook()
    .build()
}

private fun dataSource(
  postgresConfig: HikariConfig
): HikariDataSource = HikariDataSource().apply {
  jdbcUrl = postgresConfig.jdbcUrl
  driverClassName = "org.postgresql.Driver"
  username = postgresConfig.username
  password = postgresConfig.password
  maximumPoolSize = postgresConfig.maximumPoolSize
  minimumIdle = postgresConfig.minimumIdle
  connectionTimeout = postgresConfig.connectionTimeout.milliseconds.inWholeMilliseconds
  idleTimeout = postgresConfig.idleTimeout.milliseconds.inWholeMilliseconds
  maxLifetime = postgresConfig.maxLifetime.milliseconds.inWholeMilliseconds
}.also { it.validate() }

private val logger = KotlinLogging.logger("Scheduler")

fun Application.configureDbScheduler() {
  environment.monitor.subscribe(ApplicationStarted) {
    Either
      .catch {
        val scheduler = get<Scheduler>()
        createSchemaIfNotExists(get())
        scheduler.start()
      }.mapLeft {
        logger.error(it) { "an error occurred while starting the scheduler" }
      }
  }

  environment.monitor.subscribe(ApplicationStopPreparing) {
    val scheduler = get<Scheduler>()
    scheduler.stop()
  }
}

private fun createSchemaIfNotExists(postgresConfig: HikariConfig) {
  val readSqlFromResource = { name: String ->
    object {}.javaClass.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
  }
  val migration = readSqlFromResource("/dbScheduler.sql")
  val dataSource = dataSource(postgresConfig)
  logger.info { "migrating the dbScheduler if schema does not exist: $migration" }
  dataSource.use {
    it.connection.prepareStatement(migration).execute()
  }
  logger.info { "dbScheduler schema migrated successfully" }
}
