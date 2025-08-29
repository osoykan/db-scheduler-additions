package io.github.osoykan.scheduler.mongo

import com.mongodb.*
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.github.osoykan.dbscheduler.*
import io.kotest.core.spec.Spec
import org.bson.UuidRepresentation
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val MONGO_DB_NAME = "db-scheduler"

class MongoSchedulerUseCases : SchedulerUseCases<Mongo>() {
  private lateinit var mongoContainer: MongoDBContainer
  private lateinit var mongo: Mongo
  private lateinit var client: MongoClient

  override suspend fun beforeSpec(spec: Spec) {
    mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:latest")).apply {
      portBindings = listOf("27017:27017")
    }
    mongoContainer.start()

    val settings = MongoClientSettings
      .builder()
      .applyConnectionString(ConnectionString(mongoContainer.connectionString))
      .uuidRepresentation(UuidRepresentation.STANDARD)
      .readConcern(ReadConcern.MAJORITY)
      .retryWrites(true)
      .retryReads(true)
      .codecRegistry(
        PojoRegistry()
          .register<MongoTaskEntity>()
          .build()
      )
    client = MongoClient.create(settings.build())
    mongo = Mongo(client, MONGO_DB_NAME)
  }

  override suspend fun afterSpec(spec: Spec) {
    try {
      client.close()
    } catch (e: Exception) {
      logger.warn("Error closing MongoDB client", e)
    }
    try {
      mongoContainer.stop()
    } catch (e: Exception) {
      logger.warn("Error stopping MongoDB container", e)
    }
  }

  override suspend fun caseDefinition(): CaseDefinition<Mongo> = CaseDefinition(mongo) { db, tasks, startupTasks, name, clock, options ->
    scheduler {
      database(db)
      knownTasks(*tasks.toTypedArray())
      startupTasks(*startupTasks.toTypedArray())
      name(name)
      clock(clock)
      // Optimized settings for fast testing
      shutdownMaxWait(100.milliseconds)
      deleteUnresolvedAfter(1.seconds)
      fixedThreadPoolSize(options.concurrency)
      corePoolSize(2)
      heartbeatInterval(100.milliseconds) // Balanced heartbeat for stable testing
      executeDue(50.milliseconds) // Balanced polling for responsive but stable testing
    }
  }
}
