package com.github.kagkarlsson.scheduler.couchbase

import arrow.core.*
import arrow.core.Option.Companion.fromNullable
import com.fasterxml.jackson.annotation.*

private const val CAS = "cas"

internal fun Metadata.cas(): Long? = getMetadata<Long>(CAS).getOrNull()

internal fun Metadata.cas(cas: Long): Unit = setMetadata(CAS, cas)

internal interface Metadata {
  val internalMetadata: Map<String, Any>

  fun <T : Any> setMetadata(key: String, value: T)

  fun removeMetadata(key: String)

  fun hasMetadata(key: String): Boolean
}

@JsonIgnore
private inline fun <reified T> Metadata.getMetadata(key: String): Option<T> =
  internalMetadata.getOrNone(key).flatMap {
    val elem = it as? T
    fromNullable(elem)
  }

abstract class EnrichedWithMetadata : Metadata {
  @JsonProperty("metadata", access = JsonProperty.Access.READ_WRITE, defaultValue = "{}")
  private val metadata: MutableMap<String, Any> = mutableMapOf()

  @JsonIgnore
  override fun <T : Any> setMetadata(
    key: String,
    value: T
  ) {
    metadata[key] = value
  }

  @JsonIgnore
  override fun hasMetadata(key: String): Boolean = metadata.containsKey(key)

  @JsonIgnore
  override fun removeMetadata(key: String) {
    if (hasMetadata(key)) {
      metadata.remove(key)
    }
  }

  @get:JsonIgnore
  override val internalMetadata: Map<String, Any>
    get() = metadata
}
