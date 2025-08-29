@file:OptIn(ExperimentalTime::class)

package io.github.osoykan.dbscheduler

import com.github.kagkarlsson.scheduler.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.*

/**
 * A controllable test clock that can be advanced programmatically and notifies schedulers
 * when time changes to trigger immediate execution checks.
 */
class ControllableTestClock(
  initialTime: Instant = kotlin.time.Clock.System
    .now()
    .toJavaInstant()
) : Clock {
  private val currentTime = AtomicReference(initialTime)
  private val listeners = mutableListOf<() -> Unit>()

  override fun now(): Instant = currentTime.get()

  /**
   * Advance time by the specified duration and trigger scheduler execution
   */
  fun advanceBy(duration: Duration): Instant {
    val newTime = currentTime.updateAndGet { it.plus(duration.toJavaDuration()) }
    logger.debug("Advanced clock by {} to {}", duration, newTime)

    // Notify all listeners (schedulers) that time has changed
    // Use synchronized block to ensure all listeners are notified atomically
    synchronized(listeners) {
      listeners.forEach {
        try {
          it()
        } catch (e: Exception) {
          logger.warn("Error notifying time change listener", e)
        }
      }
    }

    return newTime
  }

  /**
   * Set the clock to a specific instant and trigger scheduler execution
   */
  fun setTo(instant: Instant): Instant {
    currentTime.set(instant)
    logger.debug("Set clock to {}", instant)

    // Notify all listeners (schedulers) that time has changed
    synchronized(listeners) {
      listeners.forEach {
        try {
          it()
        } catch (e: Exception) {
          logger.warn("Error notifying time change listener", e)
        }
      }
    }

    return instant
  }

  /**
   * Register a listener to be notified when time changes
   */
  fun addTimeChangeListener(listener: () -> Unit) {
    synchronized(listeners) {
      listeners.add(listener)
    }
  }

  /**
   * Get the current time plus a duration without advancing the clock
   */
  fun peekAhead(duration: Duration): Instant = now().plus(duration.toJavaDuration())
}
