package io.github.osoykan.scheduler.ui.backend.util

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal in-memory TTL cache to replace Spring-based util.Caching in future phases.
 * Not wired yet to TaskLogic/LogLogic, will be used when those services are reimplemented.
 */
class Caching<K : Any, V : Any>(
  private val ttl: Duration = Duration.ofSeconds(5)
) {
  private data class Entry<V>(val value: V, val expiresAt: Instant)

  private val store = ConcurrentHashMap<K, AtomicReference<Entry<V>>>()

  fun get(key: K, loader: () -> V): V {
    val now = Instant.now()
    val ref = store.computeIfAbsent(key) { AtomicReference() }
    val current = ref.get()
    if (current != null && current.expiresAt.isAfter(now)) {
      return current.value
    }
    val newValue = loader()
    ref.set(Entry(newValue, now.plus(ttl)))
    return newValue
  }

  fun invalidate(key: K) {
    store.remove(key)
  }

  fun clear() {
    store.clear()
  }
}
