package io.github.osoykan.scheduler

import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.concurrent.*

val CoroutineScope.asExecutorService: ExecutorService
  get() = CoroutineExecutorService(this)

internal class CoroutineExecutorService(
  private val coroutineScope: CoroutineScope
) : AbstractExecutorService() {
  override fun execute(command: Runnable) {
    coroutineScope.launch { command.run() }
  }

  override fun shutdown() {
    coroutineScope.cancel()
  }

  override fun shutdownNow(): List<Runnable> {
    coroutineScope.cancel()
    return emptyList()
  }

  override fun isShutdown(): Boolean = coroutineScope.coroutineContext[Job]?.isCancelled ?: true

  override fun isTerminated(): Boolean = coroutineScope.coroutineContext[Job]?.isCompleted ?: true

  override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
    // Coroutine jobs don't support await termination out of the box
    // This is a simplified implementation
    return isTerminated
  }
}

internal class DbSchedulerCoroutineExecutor(
  private val scope: CoroutineScope
) : Executor {
  override fun execute(command: Runnable) {
    scope.launch { command.run() }
  }
}
