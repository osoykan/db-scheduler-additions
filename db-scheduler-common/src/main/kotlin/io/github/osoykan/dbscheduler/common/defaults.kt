package io.github.osoykan.dbscheduler.common

import com.github.kagkarlsson.scheduler.Clock
import java.time.*
import java.util.concurrent.*

class UtcClock : Clock {
  override fun now(): Instant = Instant.now().atZone(ZoneOffset.UTC).toInstant()
}

class NamedThreadFactory(private val name: String) : ThreadFactory {
  private val threadFactory = Executors.defaultThreadFactory()

  override fun newThread(r: Runnable): Thread {
    val thread = threadFactory.newThread(r)
    thread.name = name + "-" + thread.name
    return thread
  }
}
