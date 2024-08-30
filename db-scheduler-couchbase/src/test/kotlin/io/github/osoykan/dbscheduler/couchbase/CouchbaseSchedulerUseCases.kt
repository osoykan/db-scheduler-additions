package io.github.osoykan.dbscheduler.couchbase

import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.codec.*
import io.github.osoykan.dbscheduler.common.*
import io.kotest.core.spec.Spec
import org.testcontainers.couchbase.*
import org.testcontainers.utility.DockerImageName
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
      this.jsonSerializer = JacksonJsonSerializer(CouchbaseScheduler.defaultObjectMapper)
      this.transcoder = JsonTranscoder(JacksonJsonSerializer(CouchbaseScheduler.defaultObjectMapper))
    }.also { it.waitUntilReady(30.seconds) }

    cluster.waitUntilReady(30.seconds)
    couchbase = Couchbase(cluster, DEFAULT_BUCKET)
  }

  override suspend fun caseDefinition(): CaseDefinition<Couchbase> =
    CaseDefinition(couchbase) { db, tasks, startupTasks, name ->
      CouchbaseScheduler.create(db, tasks, startupTasks, name)
    }

  override fun afterSpec(f: suspend (Spec) -> Unit) {
    container.stop()
  }
}
