package io.github.osoykan.scheduler.mongo

import com.mongodb.*
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.github.osoykan.dbscheduler.*
import io.kotest.core.spec.Spec
import org.bson.UuidRepresentation
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
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
      .codecRegistry(
        PojoRegistry()
          .register<MongoTaskEntity>()
          .build()
      )
    client = MongoClient.create(settings.build())
    mongo = Mongo(client, MONGO_DB_NAME)
  }

  override fun afterSpec(f: suspend (Spec) -> Unit) {
    mongoContainer.stop()
  }

  override suspend fun caseDefinition(): CaseDefinition<Mongo> = CaseDefinition(mongo) { db, tasks, startupTasks, name, clock ->
    scheduler {
      database(db)
      knownTasks(*tasks.toTypedArray())
      startupTasks(*startupTasks.toTypedArray())
      name(name)
      clock(clock)
      shutdownMaxWait(1.seconds)
    }
  }
}
