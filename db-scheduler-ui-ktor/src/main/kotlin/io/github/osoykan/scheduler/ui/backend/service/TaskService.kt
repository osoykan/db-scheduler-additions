package io.github.osoykan.scheduler.ui.backend.service

import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.ScheduledExecution
import com.github.kagkarlsson.scheduler.task.TaskInstanceId
import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.model.TaskRequestParams
import io.github.osoykan.scheduler.ui.backend.util.Caching
import java.time.Instant

/**
 * Pure-Kotlin implementation of Task logic using db-scheduler APIs (no direct SQL).
 */
internal class TaskService(
  private val scheduler: () -> Scheduler,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean
) {
  fun getAllTasks(params: TaskRequestParams): Any {
    val all = mutableListOf<ScheduledExecution<*>>()
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) { all += it }

    // Apply in-memory filters
    val filtered = all.asSequence()
      .filter { e ->
        when (params.filter) {
          TaskRequestParams.TaskFilter.SCHEDULED -> !e.isPicked
          TaskRequestParams.TaskFilter.RUNNING -> e.isPicked
          TaskRequestParams.TaskFilter.FAILED -> (e.consecutiveFailures ?: 0) > 0
          TaskRequestParams.TaskFilter.COMPLETED -> e.lastSuccess != null
          else -> true
        }
      }
      .filter { e ->
        params.searchTermTaskName?.takeIf { it.isNotBlank() }?.let { term ->
          val n = e.taskInstance.taskName
          if (params.isTaskNameExactMatch) n == term else n.contains(term, ignoreCase = true)
        } ?: true
      }
      .filter { e ->
        params.searchTermTaskInstance?.takeIf { it.isNotBlank() }?.let { term ->
          val i = e.taskInstance.id
          if (params.isTaskInstanceExactMatch) i == term else i.contains(term, ignoreCase = true)
        } ?: true
      }
      .filter { e -> params.startTime?.let { e.executionTime >= it } ?: true }
      .filter { e -> params.endTime?.let { e.executionTime <= it } ?: true }
      .toList()

    // Sort
    val comparator = when (params.sorting) {
      TaskRequestParams.TaskSort.TASK_NAME -> compareBy<ScheduledExecution<*>> { it.taskInstance.taskName }
      TaskRequestParams.TaskSort.TASK_INSTANCE -> compareBy { it.taskInstance.id }
      TaskRequestParams.TaskSort.START_TIME -> compareBy { it.executionTime }
      TaskRequestParams.TaskSort.END_TIME -> compareBy(nullsLast()) { it.lastSuccess }
      else -> compareBy { it.executionTime }
    }.let { if (params.isAsc) it else it.reversed() }

    val sorted = filtered.sortedWith(comparator)

    // Pagination
    val limit = params.size.coerceAtLeast(1)
    val page = params.pageNumber.coerceAtLeast(0)
    val from = (page * limit).coerceAtMost(sorted.size)
    val to = (from + limit).coerceAtMost(sorted.size)
    val pageItems = if (from < to) sorted.subList(from, to) else emptyList()

    val content = pageItems.map { it.toMap() }
    val total = sorted.size.toLong()
    val totalPages = if (total == 0L) 0 else ((total + limit - 1) / limit).toInt()

    return mapOf(
      "content" to content,
      "pageNumber" to params.pageNumber,
      "size" to params.size,
      "totalElements" to total,
      "totalPages" to totalPages
    )
  }

  fun getTask(params: TaskDetailsRequestParams): Any {
    val name = requireNotNull(params.taskName) { "taskName is required" }
    val id = requireNotNull(params.taskId) { "taskId is required" }

    var found: ScheduledExecution<*>? = null
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) {
      if (it.taskInstance.taskName == name && it.taskInstance.id == id) {
        found = it
      }
    }

    return mapOf(
      "task" to (found?.toMap() ?: emptyMap<String, Any?>()),
      "executions" to emptyList<Any>()
    )
  }

  fun pollTasks(params: TaskDetailsRequestParams): Any {
    // Minimal polling implementation: always not updated for now
    return mapOf(
      "updated" to false,
      "content" to emptyList<Any>()
    )
  }

  fun runTaskNow(id: String, name: String, scheduleTime: Instant): Any {
    val instance = TaskInstanceId.of(name, id)
    return try {
      scheduler().reschedule(instance, scheduleTime)
      mapOf("status" to "accepted", "id" to id, "name" to name, "scheduleTime" to scheduleTime.toString())
    } catch (_: Exception) {
      mapOf("status" to "not_found", "id" to id, "name" to name, "scheduleTime" to scheduleTime.toString())
    }
  }

  fun runTaskGroupNow(groupName: String, onlyFailed: Boolean): Any {
    val now = Instant.now()
    var updated = 0
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) { e ->
      if (e.taskInstance.taskName == groupName && (!onlyFailed || (e.consecutiveFailures ?: 0) > 0)) {
        try {
          scheduler().reschedule(e.taskInstance, now)
          updated++
        } catch (_: Exception) {
          // ignore failures for individual instances
        }
      }
    }
    return mapOf("status" to if (updated > 0) "accepted" else "not_found", "updated" to updated)
  }

  fun deleteTask(id: String, name: String): Any {
    val instance = TaskInstanceId.of(name, id)
    return try {
      scheduler().cancel(instance)
      mapOf("status" to "deleted", "id" to id, "name" to name)
    } catch (_: Exception) {
      mapOf("status" to "not_found", "id" to id, "name" to name)
    }
  }

  private fun ScheduledExecution<*>.toMap(): Map<String, Any?> = mapOf(
    "taskName" to taskInstance.taskName,
    "taskInstance" to taskInstance.id,
    "executionTime" to executionTime.toString(),
    "picked" to isPicked,
    "pickedBy" to pickedBy,
    "lastSuccess" to lastSuccess?.toString(),
    "lastFailure" to lastFailure?.toString(),
    "consecutiveFailures" to consecutiveFailures
  )
}
