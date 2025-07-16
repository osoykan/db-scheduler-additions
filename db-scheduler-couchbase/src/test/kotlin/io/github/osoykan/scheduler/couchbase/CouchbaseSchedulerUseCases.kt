package io.github.osoykan.scheduler.couchbase

import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.codec.*
import com.couchbase.client.kotlin.kv.Durability
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer.getDefaultObjectMapper
import io.github.osoykan.dbscheduler.*
import io.kotest.core.spec.Spec
import org.testcontainers.couchbase.*
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private const val BUCKET_NAME = "db-scheduler"

class CouchbaseSchedulerUseCases : SchedulerUseCases<Couchbase>() {
  private lateinit var container: CouchbaseContainer
  private lateinit var cluster: Cluster
  private lateinit var couchbase: Couchbase

  override suspend fun beforeSpec(spec: Spec) {
    val bucketDefinition = BucketDefinition(BUCKET_NAME)
      .withQuota(100)
      .withPrimaryIndex(true)

    container = CouchbaseContainer(DockerImageName.parse("couchbase/server:7.6.6"))
      .withBucket(bucketDefinition)
      .withEnabledServices(CouchbaseService.KV, CouchbaseService.QUERY, CouchbaseService.INDEX)
      .withStartupTimeout(60.seconds.toJavaDuration())
      .withStartupAttempts(3)

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
      this.transactions {
        this.durabilityLevel = Durability.persistToMajority()
      }
      compression { this.enable = true }
      this.jsonSerializer = JacksonJsonSerializer(getDefaultObjectMapper().findAndRegisterModules())
      this.transcoder = JsonTranscoder(JacksonJsonSerializer(getDefaultObjectMapper().findAndRegisterModules()))
    }

    cluster.waitUntilReady(30.seconds)
    couchbase = Couchbase(cluster, BUCKET_NAME)
  }

  override suspend fun afterSpec(spec: Spec) {
    cluster.disconnect()
    container.stop()
  }

  override suspend fun caseDefinition(): CaseDefinition<Couchbase> = CaseDefinition(couchbase) { db, tasks, startupTasks, name, clock, options ->
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
      heartbeatInterval(30.milliseconds) // Fast heartbeat for responsive testing
      executeDue(10.milliseconds) // Even faster polling for responsive testing
    }
  }
}
