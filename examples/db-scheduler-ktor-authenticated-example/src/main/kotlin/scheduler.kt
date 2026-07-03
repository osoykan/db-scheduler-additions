import arrow.core.Either
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.event.SchedulerListener
import com.github.kagkarlsson.scheduler.logging.LogLevel
import com.github.kagkarlsson.scheduler.serializer.Serializer
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.zaxxer.hikari.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.koin.core.KoinApplication
import org.koin.dsl.*
import org.koin.ktor.ext.get
import java.nio.charset.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

fun KoinApplication.registerDbScheduler() {
  modules(
    module {
      single { dbScheduler(get(), getAll(), getAll(), get()) }.bind<Scheduler>().bind<SchedulerClient>()
    }
  )
}

private fun dbScheduler(
  postgresConfig: HikariConfig,
  tasks: List<Task<*>>,
  recurringTasks: List<RecurringTask<*>>,
  listener: SchedulerListener
): Scheduler {
  val dataSource = dataSource(postgresConfig)
  return Scheduler
    .create(dataSource, tasks)
    .schedulerName(SchedulerName.Hostname())
    .alwaysPersistTimestampInUTC()
    .startTasks(recurringTasks)
    .serializer(KotlinSerializer())
    .deleteUnresolvedAfter(10.seconds.toJavaDuration())
    .shutdownMaxWait(5.seconds.toJavaDuration())
    .failureLogging(LogLevel.WARN, true)
    .threads(10)
    .registerShutdownHook()
    .addSchedulerListener(listener)
    .build()
}

private fun dataSource(
  postgresConfig: HikariConfig
): HikariDataSource = HikariDataSource()
  .apply {
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
  monitor.subscribe(ApplicationStarted) {
    Either
      .catch {
        val scheduler = get<Scheduler>()
        createSchemaIfNotExists(get())
        scheduler.start()
      }.mapLeft {
        logger.error(it) { "an error occurred while starting the scheduler" }
      }
  }

  monitor.subscribe(ApplicationStopPreparing) {
    val scheduler = get<Scheduler>()
    scheduler.stop()
  }
}

private fun createSchemaIfNotExists(postgresConfig: HikariConfig) {
  val readSqlFromResource = { name: String ->
    object {}
      .javaClass
      .getResourceAsStream(name)!!
      .bufferedReader()
      .use { it.readText() }
  }
  val migration = readSqlFromResource("/dbScheduler.sql")
  val dataSource = dataSource(postgresConfig)
  logger.info { "migrating the dbScheduler if schema does not exist: $migration" }
  dataSource.use {
    it.connection.prepareStatement(migration).execute()
  }
  logger.info { "dbScheduler schema migrated successfully" }
}

class KotlinSerializer : Serializer {
  private val charset: Charset = StandardCharsets.UTF_8

  override fun serialize(data: Any?): ByteArray {
    if (data == null) {
      return ByteArray(0)
    }
    val serializer = serializer(data.javaClass)
    return Json.encodeToString(serializer, data).toByteArray(charset)
  }

  @Suppress("UNCHECKED_CAST")
  override fun <T : Any?> deserialize(clazz: Class<T>, serializedData: ByteArray?): T? {
    if (serializedData == null || clazz == Void::class.java) {
      return null
    }

    // If the class is serialized as Any (i.e. java.lang.Object), decode as generic JSON
    if (clazz == Any::class.java) {
      return Json.decodeFromString(JsonElement.serializer(), serializedData.decodeToString()) as T
    }

    // Hackish workaround?
    // https://github.com/Kotlin/kotlinx.serialization/issues/1134
    // https://stackoverflow.com/questions/64284767/replace-jackson-with-kotlinx-serialization-in-javalin-framework/64285478#64285478
    val deserializer = serializer(clazz) as KSerializer<T>
    return Json.decodeFromString(deserializer, String(serializedData, charset))
  }
}
