package io.github.osoykan.scheduler.ui.ktor

import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.model.TaskRequestParams
import io.ktor.server.application.*
import io.ktor.util.*
import java.time.Instant

private val loader = object {}.javaClass.classLoader

/**
 * Sealed interface representing JSON values using pattern matching.
 * Supports complex type serialization including custom objects.
 */
internal sealed interface JsonVal {
  data class Obj(
    val props: Map<String, JsonVal>
  ) : JsonVal

  data class Arr(
    val items: List<JsonVal>
  ) : JsonVal

  data class Str(
    val value: String
  ) : JsonVal

  data class Num(
    val value: Number
  ) : JsonVal

  data class Bool(
    val value: Boolean
  ) : JsonVal

  data object Null : JsonVal
}

/**
 * Converts any value to JSON string in a memory-efficient and functional way.
 * Uses sequences for lazy evaluation and pattern matching for complex types.
 * Supports primitives, strings, collections, maps, and custom objects.
 *
 * @receiver Any? The value to convert to JSON
 * @return String The JSON representation of the value
 */
internal fun Any?.toJson(): String =
  this.toJsonVal().toJsonString()

/**
 * Converts a Map to JSON string in a memory-efficient and functional way.
 * Uses sequences for lazy evaluation and pattern matching for complex types.
 *
 * @receiver Map<K, V> The map to convert to JSON
 * @return String The JSON representation of the map
 */
internal fun <K, V> Map<K, V>.toJson(): String =
  this.toJsonVal().toJsonString()

/**
 * Converts any value to JsonVal using pattern matching.
 * Handles primitives, strings, collections, maps, and complex objects.
 */
private fun Any?.toJsonVal(): JsonVal = when (this) {
  null -> JsonVal.Null
  is String -> JsonVal.Str(this)
  is Number -> JsonVal.Num(this)
  is Boolean -> JsonVal.Bool(this)
  is Map<*, *> -> JsonVal.Obj(
    this.entries.associate { (k, v) ->
      k.toString() to v.toJsonVal()
    }
  )
  is Collection<*> -> JsonVal.Arr(this.map { it.toJsonVal() })
  is Array<*> -> JsonVal.Arr(this.map { it.toJsonVal() })
  else -> this.toComplexObject()
}

/**
 * Converts complex objects to JsonVal by inspecting their properties via reflection.
 * Falls back to toString() if reflection fails.
 */
private fun Any.toComplexObject(): JsonVal = try {
  val kClass = this::class
  val properties = kClass.java.declaredFields
    .filter { !it.isSynthetic }
    .associate { field ->
      field.isAccessible = true
      field.name to field.get(this).toJsonVal()
    }

  if (properties.isEmpty()) {
    JsonVal.Str(this.toString())
  } else {
    JsonVal.Obj(properties)
  }
} catch (_: Exception) {
  JsonVal.Str(this.toString())
}

/**
 * Converts JsonVal to JSON string representation.
 * Memory-efficient implementation using sequences and StringBuilder.
 */
private fun JsonVal.toJsonString(): String = when (this) {
  is JsonVal.Null -> "null"
  is JsonVal.Str -> "\"${value.escapeJson()}\""
  is JsonVal.Num -> value.toString()
  is JsonVal.Bool -> value.toString()
  is JsonVal.Obj -> buildString {
    append('{')
    props
      .asSequence()
      .map { (key, value) -> "\"${key.escapeJson()}\":${value.toJsonString()}" }
      .joinTo(this, separator = ",")
    append('}')
  }
  is JsonVal.Arr -> buildString {
    append('[')
    items
      .asSequence()
      .map { it.toJsonString() }
      .joinTo(this, separator = ",")
    append(']')
  }
}

/**
 * Escapes special characters in JSON strings according to JSON specification.
 * Memory-efficient implementation using StringBuilder.
 */
private fun String.escapeJson(): String = buildString(capacity = this@escapeJson.length) {
  for (char in this@escapeJson) {
    when (char) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\b' -> append("\\b")
      '\u000C' -> append("\\f")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      in '\u0000'..'\u001F' -> append("\\u${char.code.toString(16).padStart(4, '0')}")
      else -> append(char)
    }
  }
}

internal fun getResourceAsText(path: String): String = loader
  .getResourceAsStream(path)
  ?.let { r -> r.bufferedReader().use { it.readText() } }
  ?: ""

internal inline fun <reified T> ApplicationCall.receiveParametersTyped(): T {
  val map = parameters.flattenEntries().associateBy { it.first }.mapValues { e -> e.value.second }
  return when (T::class) {
    TaskRequestParams::class -> toTaskRequestParams(map) as T
    TaskDetailsRequestParams::class -> toTaskDetailsRequestParams(map) as T
    else -> throw IllegalArgumentException("Unsupported type")
  }
}

internal fun toTaskDetailsRequestParams(map: Map<String, String>): TaskDetailsRequestParams {
  val trp = toTaskRequestParams(map)
  val taskId = map["taskId"]
  val taskName = map["taskName"]
  return TaskDetailsRequestParams(
    trp.filter,
    trp.pageNumber,
    trp.size,
    trp.sorting,
    trp.isAsc,
    trp.searchTermTaskName,
    trp.searchTermTaskInstance,
    trp.isTaskNameExactMatch,
    trp.isTaskInstanceExactMatch,
    trp.startTime,
    trp.endTime,
    taskName,
    taskId,
    trp.isRefresh
  )
}

internal fun toTaskRequestParams(map: Map<String, String>): TaskRequestParams {
  val filter = map["filter"]?.let { TaskRequestParams.TaskFilter.valueOf(it) } ?: TaskRequestParams.TaskFilter.ALL
  val pageNumber = map["pageNumber"]?.toInt() ?: 0
  val size = map["size"]?.toInt() ?: 10
  val sorting = map["sorting"]?.let { TaskRequestParams.TaskSort.valueOf(it) } ?: TaskRequestParams.TaskSort.DEFAULT
  val asc = map["asc"]?.toBoolean() ?: true
  val searchTermTaskName = map["searchTermTaskName"]
  val searchTermTaskInstance = map["searchTermTaskInstance"]
  val taskNameExactMatch = map["taskNameExactMatch"]?.toBoolean() ?: false
  val taskInstanceExactMatch = map["taskInstanceExactMatch"]?.toBoolean() ?: false
  val startTime = map["startTime"]?.let { Instant.parse(it) }
  val endTime = map["endTime"]?.let { Instant.parse(it) }
  val refresh = map["refresh"]?.toBoolean() ?: true
  return TaskRequestParams(
    filter,
    pageNumber,
    size,
    sorting,
    asc,
    searchTermTaskName,
    searchTermTaskInstance,
    taskNameExactMatch,
    taskInstanceExactMatch,
    startTime,
    endTime,
    refresh
  )
}
