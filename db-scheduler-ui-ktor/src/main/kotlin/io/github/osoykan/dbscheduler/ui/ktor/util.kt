package io.github.osoykan.dbscheduler.ui.ktor

import io.ktor.server.application.*
import io.ktor.util.*
import no.bekk.dbscheduler.ui.model.*
import java.time.Instant

private val loader = object {}.javaClass.classLoader

internal fun getResourceAsText(path: String): String = loader.getResourceAsStream(path)
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
