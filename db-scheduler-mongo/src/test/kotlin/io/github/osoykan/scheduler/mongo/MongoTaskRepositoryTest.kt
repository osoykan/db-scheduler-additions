package io.github.osoykan.scheduler.mongo

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kagkarlsson.scheduler.*
import com.github.kagkarlsson.scheduler.event.SchedulerListeners
import com.github.kagkarlsson.scheduler.exceptions.ExecutionException
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer.getDefaultObjectMapper
import com.github.kagkarlsson.scheduler.task.*
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.mongodb.*
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.github.osoykan.dbscheduler.*
import io.github.osoykan.scheduler.UtcClock
import io.github.osoykan.scheduler.documentId
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.*
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.UuidRepresentation
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class MongoTaskRepositoryTest : AnnotationSpec() {
  private lateinit var mongoContainer: MongoDBContainer
  private lateinit var client: MongoClient
  private val clock = UtcClock()

  // A registered task so executions resolve to a real data class.
  private val task = Tasks
    .oneTime("repo-test-task", TestTaskData::class.java)
    .execute { _, _ -> }
  private val taskResolver = TaskResolver(SchedulerListeners(emptyList()), clock, listOf(task))
  private val serializer = JacksonSerializer(getDefaultObjectMapper().findAndRegisterModules().registerKotlinModule())

  override suspend fun beforeSpec(spec: Spec) {
    // No fixed port binding: this spec runs concurrently with others, so let
    // testcontainers assign a random host port to avoid collisions.
    mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:latest"))
    mongoContainer.start()
    val settings = MongoClientSettings
      .builder()
      .applyConnectionString(ConnectionString(mongoContainer.connectionString))
      .uuidRepresentation(UuidRepresentation.STANDARD)
      .codecRegistry(PojoRegistry().register<MongoTaskEntity>().build())
      .build()
    client = MongoClient.create(settings)
  }

  override suspend fun afterSpec(spec: Spec) {
    runCatching { client.close() }
    runCatching { mongoContainer.stop() }
  }

  private suspend fun freshRepo(): Pair<MongoTaskRepository, Mongo> {
    val mongo = Mongo(client, "db-scheduler-repo-test", ARandom.text()).also { it.ensureCollectionExists() }
    val repo = MongoTaskRepository(clock, mongo, taskResolver, SchedulerName.Fixed(ARandom.text()), serializer)
    repo.createIndexes()
    return repo to mongo
  }

  private fun instance(id: String = ARandom.text()): ScheduledTaskInstance =
    ScheduledTaskInstance(task.instance(id, TestTaskData("data")), clock.now())

  private suspend fun MongoTaskRepository.only(): Execution {
    val list = mutableListOf<Execution>()
    getScheduledExecutions(ScheduledExecutionsFilter.all()) { list.add(it) }
    return list.single()
  }

  // ---- Bug #1: remove() must be version-guarded and throw when nothing removed ----

  @Test
  suspend fun `remove with stale version does not delete and throws`() {
    val (repo, _) = freshRepo()
    val inst = instance()
    repo.createIfNotExists(inst)
    val stored = repo.only()
    // Simulate a concurrent modification: pick() bumps the persisted version so
    // the caller's Execution is now stale.
    repo.pick(stored, clock.now())
    val staleExecution = stored // still carries the old version

    val ex = runCatching { repo.remove(staleExecution) }.exceptionOrNull()
    ex.shouldBeInstanceOf<ExecutionException>()
    // The row must still be there since the version did not match.
    repo.getExecution(inst.taskName, inst.id).isPresent shouldBe true
  }

  @Test
  suspend fun `remove with current version deletes the row`() {
    val (repo, _) = freshRepo()
    val inst = instance()
    repo.createIfNotExists(inst)
    val stored = repo.only()

    repo.remove(stored)

    repo.getExecution(inst.taskName, inst.id).isPresent shouldBe false
  }

  // ---- Bug #2: getExecutionsFailingLongerThan predicate ----

  @Test
  suspend fun `failing longer than gates on consecutiveFailures not lastFailure recency`() {
    val (repo, mongo) = freshRepo()
    val boundary = clock.now()
    // Task that is currently failing (consecutiveFailures > 0), never succeeded,
    // but whose last failure is RECENT (after the boundary). The reference SQL
    // returns this; the buggy Mongo predicate (lastFailure < boundary) excludes it.
    mongo.schedulerCollection.insertOne(
      entity(taskInstance = "failing-recent", consecutiveFailures = 2, lastSuccess = null, lastFailure = boundary.plusSeconds(5))
    )

    val result = repo.getExecutionsFailingLongerThan(1.minutes.toJavaDuration())

    result.map { it.taskInstance.id } shouldContain "failing-recent"
  }

  @Test
  suspend fun `failing longer than excludes recovered tasks with zero consecutiveFailures`() {
    val (repo, mongo) = freshRepo()
    val boundary = clock.now()
    // Recovered task: a stale lastFailure remains but consecutiveFailures == 0.
    // Reference excludes it (consecutive_failures > 0 required).
    mongo.schedulerCollection.insertOne(
      entity(taskInstance = "recovered", consecutiveFailures = 0, lastSuccess = null, lastFailure = boundary.minusSeconds(120))
    )

    val result = repo.getExecutionsFailingLongerThan(1.minutes.toJavaDuration())

    result.map { it.taskInstance.id } shouldNotContain "recovered"
  }

  // ---- Bug #3: updateHeartbeat must use matchedCount, not modifiedCount ----

  @Test
  suspend fun `updateHeartbeat returns true even when heartbeat value is unchanged`() {
    val (repo, _) = freshRepo()
    val inst = instance()
    repo.createIfNotExists(inst)
    val stored = repo.only()
    val hb = clock.now()

    repo.updateHeartbeat(stored, hb) shouldBe true
    // Writing the exact same heartbeat again: modifiedCount would be 0, but the
    // row still matches the version, so this must be reported as success.
    repo.updateHeartbeat(stored, hb) shouldBe true
  }

  // ---- Div #6: getDeadExecutions boundary should be inclusive (<=) ----

  @Test
  suspend fun `getDeadExecutions includes execution whose heartbeat equals the boundary`() {
    val (repo, mongo) = freshRepo()
    val boundary = clock.now().truncatedTo(ChronoUnit.MILLIS)
    mongo.schedulerCollection.insertOne(
      entity(taskInstance = "dead-on-boundary", picked = true, lastHeartbeat = boundary)
    )

    val dead = repo.getDeadExecutions(boundary)

    dead.map { it.taskInstance.id } shouldContain "dead-on-boundary"
  }

  // ---- Div #7: replace resets version to absolute 1 ----

  @Test
  suspend fun `replace resets version to 1`() {
    val (repo, mongo) = freshRepo()
    val inst = instance()
    repo.createIfNotExists(inst)
    // pick() bumps the version, so it is clearly > 1 before replace.
    val created = repo.only()
    repo.pick(created, clock.now())
    val stored = repo.only()
    stored.version shouldBeGreaterThan 1L

    repo.replace(stored, instance(inst.id))

    val replaced = mongo.schedulerCollection
      .find(Filters.eq(MongoTaskEntity::identity.name, stored.documentId()))
      .firstOrNull()!!
    replaced.version shouldBe 1L
  }

  // ---- Div #5: createBatch must rethrow non-duplicate write errors ----

  @Test
  suspend fun `createBatch ignores duplicate keys`() {
    val (repo, _) = freshRepo()
    val inst = instance()
    repo.createIfNotExists(inst)
    // Batch contains an existing instance (duplicate) plus a new one.
    repo.createBatch(listOf(ScheduledTaskInstance(task.instance(inst.id, TestTaskData("x")), clock.now()), instance()))
    // Should not throw, and the new one should be inserted.
    val all = mutableListOf<Execution>()
    repo.getScheduledExecutions(ScheduledExecutionsFilter.all()) { all.add(it) }
    all.size shouldBe 2
  }

  @Test
  suspend fun `createBatch rethrows non-duplicate write errors`() {
    val (repo, _) = freshRepo()
    // A taskData that violates the 16MB BSON document limit forces a genuine
    // (non-duplicate) write error. The buggy implementation swallows ALL
    // exceptions; the fixed one must rethrow anything that is not a duplicate key.
    val huge = TestTaskData("x".repeat(17 * 1024 * 1024))
    val poison = ScheduledTaskInstance(task.instance("poison", huge), clock.now())

    val thrown = runCatching { repo.createBatch(listOf(poison)) }.exceptionOrNull()

    thrown.shouldNotBeNull()
  }

  // ---- Perf: compound indexes for the hot polling/query paths ----

  @Test
  suspend fun `createIndexes creates compound indexes covering the hot queries`() {
    val (_, mongo) = freshRepo()

    val keyPatterns = mongo.schedulerCollection
      .listIndexes()
      .toList()
      .mapNotNull { it["key"] as? org.bson.Document }
      .map { it.keys.toList() }
      .toSet()

    val picked = MongoTaskEntity::picked.name
    val executionTime = MongoTaskEntity::executionTime.name
    val lastHeartbeat = MongoTaskEntity::lastHeartbeat.name
    val taskName = MongoTaskEntity::taskName.name

    // getDue / lockAndFetchGeneric: equality(picked) + range/sort(executionTime)
    keyPatterns shouldContain listOf(picked, executionTime)
    // getDeadExecutions: equality(picked) + range/sort(lastHeartbeat)
    keyPatterns shouldContain listOf(picked, lastHeartbeat)
    // getScheduledExecutions(taskName, ...): equality(taskName, picked) + sort(executionTime)
    keyPatterns shouldContain listOf(taskName, picked, executionTime)
  }

  private fun entity(
    taskInstance: String,
    picked: Boolean = false,
    consecutiveFailures: Int = 0,
    lastSuccess: Instant? = null,
    lastFailure: Instant? = null,
    lastHeartbeat: Instant? = null,
    version: Long = 1L
  ): MongoTaskEntity = MongoTaskEntity(
    taskName = task.name,
    taskInstance = taskInstance,
    taskData = serializer.serialize(TestTaskData("data")),
    executionTime = clock.now(),
    picked = picked,
    pickedBy = if (picked) "tester" else null,
    consecutiveFailures = consecutiveFailures,
    lastSuccess = lastSuccess,
    lastFailure = lastFailure,
    lastHeartbeat = lastHeartbeat,
    version = version
  )
}
