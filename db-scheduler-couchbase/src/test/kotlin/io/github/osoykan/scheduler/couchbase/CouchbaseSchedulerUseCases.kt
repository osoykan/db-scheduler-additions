package io.github.osoykan.scheduler.couchbase

import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.codec.*
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer.getDefaultObjectMapper
import io.github.osoykan.dbscheduler.*
import io.kotest.core.spec.Spec
import org.testcontainers.couchbase.*
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_BUCKET = "db-scheduler"

class CouchbaseSchedulerUseCases : SchedulerUseCases<Couchbase>() {
  private val container = CouchbaseContainer(DockerImageName.parse("couchbase/server:7.6.2"))
    .apply {
      withBucket(BucketDefinition(DEFAULT_BUCKET))
      withEnabledServices(CouchbaseService.KV, CouchbaseService.QUERY, CouchbaseService.INDEX)
      withStartupAttempts(3)
    }
  private lateinit var cluster: Cluster
  private lateinit var couchbase: Couchbase

  override suspend fun beforeSpec(spec: Spec) {
    container.start()
    val connectionString = container.connectionString
    val username = container.username
    val password = container.password
    cluster = Cluster.connect(connectionString, username, password) {
      timeout {
        this.kvTimeout = 30.seconds
        this.queryTimeout = 30.seconds
        this.viewTimeout = 30.seconds
        this.connectTimeout = 30.seconds
        this.disconnectTimeout = 30.seconds
        this.kvDurableTimeout = 30.seconds
        this.kvScanTimeout = 30.seconds
        this.searchTimeout = 30.seconds
      }
      compression { this.enable = true }
      this.jsonSerializer = JacksonJsonSerializer(getDefaultObjectMapper().findAndRegisterModules())
      this.transcoder = JsonTranscoder(JacksonJsonSerializer(getDefaultObjectMapper().findAndRegisterModules()))
    }

    cluster.waitUntilReady(30.seconds)
    couchbase = Couchbase(cluster, DEFAULT_BUCKET)
  }

  override fun afterSpec(f: suspend (Spec) -> Unit) {
    container.stop()
  }

  override suspend fun caseDefinition(): CaseDefinition<Couchbase> = CaseDefinition(couchbase) { db, tasks, startupTasks, name, clock ->
    scheduler {
      database(db)
      knownTasks(*tasks.toTypedArray())
      startupTasks(*startupTasks.toTypedArray())
      name(name)
      clock(clock)
      shutdownMaxWait(1.seconds)
      deleteUnresolvedAfter(10.minutes)
    }
  }
}
