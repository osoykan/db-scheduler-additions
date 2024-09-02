@file:Suppress("LeakingThis")

package io.github.osoykan.dbscheduler

import io.kotest.common.ExperimentalKotest
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.LoggerFactory

@ExperimentalKotest
open class Config : AbstractProjectConfig() {
  private val logger = LoggerFactory.getLogger("DbSchedulerCommonTestConfig")
  override val parallelism: Int = Runtime.getRuntime().availableProcessors()
  override val concurrentTests: Int = Runtime.getRuntime().availableProcessors()

  init {
    logger.debug("the testing is configured with parallelism: $parallelism and concurrentTests: $concurrentTests")
  }
}
