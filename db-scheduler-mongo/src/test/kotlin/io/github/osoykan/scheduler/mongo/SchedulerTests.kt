package io.github.osoykan.scheduler.mongo

import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.mongodb.*
import com.mongodb.MongoClientSettings.getDefaultCodecRegistry
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bson.UuidRepresentation
import org.bson.codecs.configuration.CodecRegistries.*
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.pojo.PojoCodecProvider
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

class PojoRegistry {
  private var builder = PojoCodecProvider.builder().automatic(true)

  inline fun <reified T : Any> register(): PojoRegistry = register(T::class)

  fun <T : Any> register(clazz: KClass<T>): PojoRegistry = builder.register(clazz.java).let { this }

  fun build(): CodecRegistry = fromRegistries(
    getDefaultCodecRegistry(),
    fromProviders(builder.build())
  )
}

class SchedulerTests : FunSpec({
  test("should work") {
    val mongodb = MongoDBContainer(DockerImageName.parse("mongo:latest")).apply {
      portBindings = listOf("27017:27017")
    }
    mongodb.start()

    println("MongoDB URL: ${mongodb.replicaSetUrl}")

    val settings = MongoClientSettings
      .builder()
      .applyConnectionString(ConnectionString(mongodb.connectionString))
      .uuidRepresentation(UuidRepresentation.STANDARD)
      .readConcern(ReadConcern.MAJORITY)
      .codecRegistry(
        PojoRegistry()
          .register(TaskEntity::class)
          .build()
      )
    val client = MongoClient.create(settings.build())
    val mongo = Mongo(client, "scheduler")
    mongo.ensurePreferredCollectionExists()

    var invoked = 0
    val oneTimeTask = Tasks.oneTime("one-time-task")
      .execute { _, _ ->
        invoked++
        println("Executing one-time task")
      }

    val scheduler = MongoScheduler.create(mongo, listOf(oneTimeTask))
    scheduler.start()
    scheduler.schedule(oneTimeTask.instance("1"), Instant.now())

    eventually(10.seconds) {
      invoked shouldBe 1
    }

    scheduler.stop()
  }
})
