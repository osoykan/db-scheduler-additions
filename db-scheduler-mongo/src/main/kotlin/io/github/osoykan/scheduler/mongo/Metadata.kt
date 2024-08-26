package io.github.osoykan.scheduler.mongo

import arrow.core.*
import arrow.core.Option.Companion.fromNullable
import com.fasterxml.jackson.annotation.*

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

abstract class WithMetadata : Metadata {
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
