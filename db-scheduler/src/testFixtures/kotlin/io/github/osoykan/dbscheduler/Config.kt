@file:Suppress("LeakingThis")

package io.github.osoykan.dbscheduler

import io.kotest.common.ExperimentalKotest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.*
import org.slf4j.LoggerFactory

@ExperimentalKotest
open class Config : AbstractProjectConfig() {
  private val logger = LoggerFactory.getLogger("DbSchedulerCommonTestConfig")
  override val testExecutionMode: TestExecutionMode = TestExecutionMode.LimitedConcurrency(Runtime.getRuntime().availableProcessors())
  override val specExecutionMode: SpecExecutionMode = SpecExecutionMode.LimitedConcurrency(Runtime.getRuntime().availableProcessors())
  override val failfast: Boolean = false
  override val projectWideFailFast: Boolean = false

  init {
    logger.info("DbSchedulerCommonTestConfig initialized with ${testExecutionMode.concurrency} concurrency level")
  }
}
