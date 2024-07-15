@file:Suppress("UNCHECKED_CAST")

package com.github.kagkarlsson.scheduler.couchbase

import com.couchbase.client.kotlin.Cluster
import com.couchbase.client.kotlin.codec.*
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.Task
import com.trendyol.stove.testing.e2e.couchbase.*
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import io.kotest.common.ExperimentalKotest
import io.kotest.core.config.AbstractProjectConfig
import org.testcontainers.couchbase.CouchbaseService
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

typealias SchedulerFactory = (List<Task<*>>, String) -> Scheduler

private const val DEFAULT_BUCKET = "db-scheduler"

@ExperimentalKotest
class Stove : AbstractProjectConfig() {
  override val concurrentSpecs: Int = 1
  override val concurrentTests: Int = 1

  override suspend fun beforeProject() = TestSystem {
    enableReuseForTestContainers()
    keepDependenciesRunning()
  }.with {
    couchbase {
      CouchbaseSystemOptions(
        defaultBucket = DEFAULT_BUCKET,
        objectMapper = StoveObjectMapper.Default,
        containerOptions = CouchbaseContainerOptions {
          withEnabledServices(CouchbaseService.KV, CouchbaseService.QUERY, CouchbaseService.INDEX)
          withStartupAttempts(3)
        },
        configureExposedConfiguration = { cfg ->
          listOf(
            "host=${cfg.hostsWithPort}",
            "username=${cfg.username}",
            "password=${cfg.password}"
          )
        }
      )
    }

    bridge(object : BridgeSystem<SchedulerFactory>(testSystem) {
      override fun <D : Any> get(klass: KClass<D>): D = DbSchedulerApp.createScheduler as D
    })

    applicationUnderTest(DbSchedulerApp())
  }.run()

  override suspend fun afterProject() {
    TestSystem.stop()
  }

  class DbSchedulerApp : ApplicationUnderTest<SchedulerFactory> {
    companion object {
      lateinit var createScheduler: (List<Task<*>>, String) -> Scheduler
    }

    override suspend fun start(configurations: List<String>): SchedulerFactory {
      val connectionString = parseCommandLineParametersAsKeyValue(key = "host", configurations)
      val username = parseCommandLineParametersAsKeyValue(key = "username", configurations)
      val password = parseCommandLineParametersAsKeyValue(key = "password", configurations)
      val cluster = Cluster.connect(connectionString, username, password) {
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
      }.also { it.waitUntilReady(20.seconds) }

      val couchbase = Couchbase(cluster, DEFAULT_BUCKET)
      createScheduler = { tasks: List<Task<*>>, name: String -> CouchbaseScheduler.create(couchbase, knownTasks = tasks, name) }
      return createScheduler
    }

    override suspend fun stop() = Unit

    private fun parseCommandLineParametersAsKeyValue(key: String, configurations: List<String>): String {
      val keyValue = configurations.find { it.startsWith(key) }
      return keyValue?.split("=")?.get(1) ?: throw IllegalArgumentException("Key not found")
    }
  }
}

@StoveDsl
suspend fun scheduler(
  name: String,
  vararg tasks: Task<*>,
  block: suspend Scheduler.() -> Unit
) = validate {
  using<SchedulerFactory> {
    val scheduler = this(tasks.toList(), name)
    scheduler.start()
    block(scheduler)
    scheduler.stop()
  }
}
