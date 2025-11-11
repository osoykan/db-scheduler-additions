package io.github.osoykan.scheduler.ui.backend.service

import com.github.kagkarlsson.scheduler.ScheduledExecution
import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.TaskInstanceId
import io.github.osoykan.scheduler.ui.backend.model.*
import io.github.osoykan.scheduler.ui.backend.util.Caching
import java.time.Instant

/**
 * Pure-Kotlin implementation of Task logic using db-scheduler APIs (no direct SQL).
 * Refactored into functional compositional parts for better maintainability.
 * Returns maps instead of data classes to avoid serialization configuration dependencies.
 */
internal class TaskService(
  private val scheduler: () -> Scheduler,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean
) {
  // ==================== Public API ====================

  fun getAllTasks(params: TaskRequestParams): Map<String, Any> {
    val executions = fetchAllExecutions()
    val filtered = applyFilters(executions, params)
    val taskMaps = groupAndMapExecutions(filtered)
    val sorted = sortTasks(taskMaps, params.sorting, params.isAsc)
    return paginateTasks(sorted, params.pageNumber, params.size)
  }

  fun getTask(params: TaskDetailsRequestParams): Map<String, Any> {
    val executions = fetchExecutionsBy(taskName = params.taskName, taskId = params.taskId)

    if (executions.isEmpty()) {
      return buildEmptyTasksResponse()
    }

    val taskMaps = groupAndMapExecutions(executions)
    return buildTasksResponse(taskMaps, totalPages = 1)
  }

  fun pollTasks(params: TaskDetailsRequestParams): Map<String, Any> {
    val cacheKey = buildPollCacheKey(params)
    val currentTasks = getAllTasksInternal(params)
    val currentStates = extractTaskStates(currentTasks)
    val previousStates = getPreviousStates(cacheKey)
    val changes = calculateTaskChanges(previousStates, currentStates)

    updatePollCache(cacheKey, currentStates)

    return changes
  }

  fun runTaskNow(id: String, name: String, scheduleTime: Instant): Map<String, Any?> {
    val instance = TaskInstanceId.of(name, id)
    return executeTaskAction(instance) {
      scheduler().reschedule(instance, scheduleTime)
      buildTaskActionResponse("accepted", id, name, scheduleTime)
    }
  }

  fun runTaskGroupNow(groupName: String, onlyFailed: Boolean): Map<String, Any?> {
    val now = Instant.now()
    val updated = rescheduleTaskGroup(groupName, onlyFailed, now)
    return buildGroupActionResponse(updated)
  }

  fun deleteTask(id: String, name: String): Map<String, Any?> {
    val instance = TaskInstanceId.of(name, id)
    return executeTaskAction(instance) {
      scheduler().cancel(instance)
      buildTaskActionResponse("deleted", id, name)
    }
  }

  // ==================== Execution Fetching ====================

  private fun fetchAllExecutions(): List<ScheduledExecution<*>> {
    val executions = mutableListOf<ScheduledExecution<*>>()
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) { executions += it }
    return executions
  }

  private fun fetchExecutionsBy(taskName: String?, taskId: String?): List<ScheduledExecution<*>> {
    val executions = mutableListOf<ScheduledExecution<*>>()
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) { execution ->
      val matchesName = taskName == null || execution.taskInstance.taskName == taskName
      val matchesId = taskId == null || execution.taskInstance.id == taskId
      if (matchesName && matchesId) {
        executions += execution
      }
    }
    return executions
  }

  // ==================== Filtering ====================

  private fun applyFilters(
    executions: List<ScheduledExecution<*>>,
    params: TaskRequestParams
  ): List<ScheduledExecution<*>> = executions
    .asSequence()
    .filter { applyStatusFilter(it, params.filter) }
    .filter { applyTaskNameFilter(it, params.searchTermTaskName, params.isTaskNameExactMatch) }
    .filter { applyTaskInstanceFilter(it, params.searchTermTaskInstance, params.isTaskInstanceExactMatch) }
    .filter { applyTimeRangeFilter(it, params.startTime, params.endTime) }
    .toList()

  private fun applyStatusFilter(execution: ScheduledExecution<*>, filter: TaskRequestParams.TaskFilter): Boolean = when (filter) {
    TaskRequestParams.TaskFilter.SCHEDULED -> !execution.isPicked
    TaskRequestParams.TaskFilter.RUNNING -> execution.isPicked
    TaskRequestParams.TaskFilter.FAILED -> execution.consecutiveFailures > 0
    TaskRequestParams.TaskFilter.COMPLETED -> execution.lastSuccess != null
    TaskRequestParams.TaskFilter.ALL -> true
  }

  private fun applyTaskNameFilter(execution: ScheduledExecution<*>, searchTerm: String?, exactMatch: Boolean): Boolean {
    val term = searchTerm?.takeIf { it.isNotBlank() } ?: return true
    val taskName = execution.taskInstance.taskName
    return if (exactMatch) taskName == term else taskName.contains(term, ignoreCase = true)
  }

  private fun applyTaskInstanceFilter(execution: ScheduledExecution<*>, searchTerm: String?, exactMatch: Boolean): Boolean {
    val term = searchTerm?.takeIf { it.isNotBlank() } ?: return true
    val instanceId = execution.taskInstance.id
    return if (exactMatch) instanceId == term else instanceId.contains(term, ignoreCase = true)
  }

  private fun applyTimeRangeFilter(execution: ScheduledExecution<*>, startTime: Instant?, endTime: Instant?): Boolean {
    val matchesStart = startTime?.let { execution.executionTime >= it } ?: true
    val matchesEnd = endTime?.let { execution.executionTime <= it } ?: true
    return matchesStart && matchesEnd
  }

  // ==================== Task Mapping ====================

  private fun groupAndMapExecutions(executions: List<ScheduledExecution<*>>): List<Map<String, Any?>> = executions
    .groupBy { it.taskInstance.taskName }
    .map { (taskName, groupedExecutions) -> mapExecutionsToTask(taskName, groupedExecutions) }

  private fun mapExecutionsToTask(taskName: String, executions: List<ScheduledExecution<*>>): Map<String, Any?> = mapOf(
    "taskName" to taskName,
    "taskInstance" to executions.map { it.taskInstance.id },
    "taskData" to mapTaskData(executions),
    "executionTime" to executions.map { it.executionTime.toString() },
    "picked" to executions.any { it.isPicked },
    "pickedBy" to executions.map { it.pickedBy },
    "lastSuccess" to executions.map { it.lastSuccess?.toString() },
    "lastFailure" to executions.mapNotNull { it.lastFailure }.maxByOrNull { it }?.toString(),
    "consecutiveFailures" to executions.map { it.consecutiveFailures },
    "lastHeartbeat" to null, // Not available in ScheduledExecution
    "version" to 1 // Not available in ScheduledExecution, using default
  )

  private fun mapTaskData(executions: List<ScheduledExecution<*>>): List<Any?> = if (taskData) {
    executions.map {
      it.data
    }
  } else {
    executions.map { null }
  }

  // ==================== Sorting & Pagination ====================

  private fun sortTasks(tasks: List<Map<String, Any?>>, sorting: TaskRequestParams.TaskSort, ascending: Boolean): List<Map<String, Any?>> {
    val sorted = when (sorting) {
      TaskRequestParams.TaskSort.TASK_NAME -> tasks.sortedBy { it["taskName"] as String }
      else -> tasks.sortedBy { it["taskName"] as String } // Default sort by name
    }
    return if (ascending) sorted else sorted.reversed()
  }

  private fun paginateTasks(tasks: List<Map<String, Any?>>, pageNumber: Int, pageSize: Int): Map<String, Any> {
    val limit = pageSize.coerceAtLeast(1)
    val page = pageNumber.coerceAtLeast(0)
    val from = (page * limit).coerceAtMost(tasks.size)
    val to = (from + limit).coerceAtMost(tasks.size)
    val pageItems = if (from < to) tasks.subList(from, to) else emptyList()
    val totalPages = if (tasks.isEmpty()) 0 else ((tasks.size + limit - 1) / limit)

    return buildTasksResponse(pageItems, tasks.size, totalPages)
  }

  // ==================== Polling ====================

  private fun getAllTasksInternal(params: TaskDetailsRequestParams): List<Map<String, Any>> {
    val taskParams = TaskRequestParams(
      filter = params.filter,
      pageNumber = params.pageNumber,
      size = params.size,
      sorting = params.sorting,
      isAsc = params.isAsc,
      searchTermTaskName = params.searchTermTaskName,
      searchTermTaskInstance = params.searchTermTaskInstance,
      isTaskNameExactMatch = params.isTaskNameExactMatch,
      isTaskInstanceExactMatch = params.isTaskInstanceExactMatch,
      startTime = params.startTime,
      endTime = params.endTime,
      isRefresh = params.isRefresh
    )
    @Suppress("UNCHECKED_CAST")
    return getAllTasks(taskParams)["items"] as List<Map<String, Any>>
  }

  @Suppress("UNCHECKED_CAST")
  private fun extractTaskStates(tasks: List<Map<String, Any>>): Map<String, TaskState> = tasks.associate { task ->
    (task["taskName"] as String) to TaskState(
      isRunning = task["picked"] as Boolean,
      hasFailed = task["lastFailure"] != null,
      hasSucceeded = (task["lastSuccess"] as List<*>).any { it != null },
      consecutiveFailures = (task["consecutiveFailures"] as List<Int>).maxOrNull() ?: 0
    )
  }

  private fun getPreviousStates(cacheKey: String): Map<String, TaskState> {
    val pollState = caching.get(cacheKey) {
      PollState(previousTasks = emptyMap(), currentTasks = emptyMap())
    } as PollState
    return pollState.currentTasks
  }

  private fun updatePollCache(cacheKey: String, currentStates: Map<String, TaskState>) {
    caching.invalidate(cacheKey)
    caching.get(cacheKey) {
      PollState(previousTasks = emptyMap(), currentTasks = currentStates)
    }
  }

  private fun buildPollCacheKey(params: TaskDetailsRequestParams): String = "poll_${params.hashCode()}"

  private fun calculateTaskChanges(previous: Map<String, TaskState>, current: Map<String, TaskState>): Map<String, Any> {
    val changes = TaskChanges()

    for ((taskName, currentState) in current) {
      val previousState = previous[taskName]

      if (previousState == null) {
        changes.trackNewTask(currentState)
      } else {
        changes.trackStateChanges(previousState, currentState)
      }
    }

    return changes.toMap()
  }

  // ==================== Task Actions ====================

  private fun executeTaskAction(instance: TaskInstanceId, action: () -> Map<String, Any?>): Map<String, Any?> = try {
    action()
  } catch (_: Exception) {
    mapOf(
      "status" to "not_found",
      "id" to instance.id,
      "name" to instance.taskName
    )
  }

  private fun rescheduleTaskGroup(groupName: String, onlyFailed: Boolean, scheduleTime: Instant): Int {
    var updated = 0
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) { execution ->
      val isTargetGroup = execution.taskInstance.taskName == groupName
      val shouldReschedule = !onlyFailed || execution.consecutiveFailures > 0

      if (isTargetGroup && shouldReschedule) {
        try {
          scheduler().reschedule(execution.taskInstance, scheduleTime)
          updated++
        } catch (_: Exception) {
          // Ignore failures for individual instances
        }
      }
    }
    return updated
  }

  // ==================== Response Builders ====================

  private fun buildTasksResponse(
    items: List<Map<String, Any?>>,
    totalItems: Int = items.size,
    totalPages: Int = 1
  ): Map<String, Any> = mapOf(
    "items" to items,
    "numberOfItems" to totalItems,
    "numberOfPages" to totalPages
  )

  private fun buildEmptyTasksResponse(): Map<String, Any> = mapOf(
    "items" to emptyList<Map<String, Any?>>(),
    "numberOfItems" to 0,
    "numberOfPages" to 0
  )

  private fun buildTaskActionResponse(
    status: String,
    id: String,
    name: String,
    scheduleTime: Instant? = null
  ): Map<String, Any?> = mapOf(
    "status" to status,
    "id" to id,
    "name" to name,
    "scheduleTime" to scheduleTime?.toString()
  )

  private fun buildGroupActionResponse(updated: Int): Map<String, Any?> = mapOf(
    "status" to if (updated > 0) "accepted" else "not_found",
    "updated" to updated
  )

  // ==================== Internal Models ====================

  private data class TaskState(
    val isRunning: Boolean,
    val hasFailed: Boolean,
    val hasSucceeded: Boolean,
    val consecutiveFailures: Int
  )

  private data class PollState(
    val previousTasks: Map<String, TaskState>,
    val currentTasks: Map<String, TaskState>
  )

  private class TaskChanges {
    var newFailures = 0
    var newRunning = 0
    var newTasks = 0
    var newSucceeded = 0
    var stoppedFailing = 0
    var finishedRunning = 0

    fun trackNewTask(state: TaskState) {
      newTasks++
      if (state.isRunning) newRunning++
      if (state.hasFailed && state.consecutiveFailures > 0) newFailures++
      if (state.hasSucceeded) newSucceeded++
    }

    fun trackStateChanges(previous: TaskState, current: TaskState) {
      // New failures: increased consecutive failures
      if (current.consecutiveFailures > previous.consecutiveFailures) {
        newFailures++
      }

      // Stopped failing: had failures before, now has none
      if (previous.consecutiveFailures > 0 && current.consecutiveFailures == 0) {
        stoppedFailing++
      }

      // New running: wasn't running before, is running now
      if (!previous.isRunning && current.isRunning) {
        newRunning++
      }

      // Finished running: was running before, not running now
      if (previous.isRunning && !current.isRunning) {
        finishedRunning++
      }

      // New succeeded: wasn't succeeded before, is succeeded now
      if (!previous.hasSucceeded && current.hasSucceeded) {
        newSucceeded++
      }
    }

    fun toMap(): Map<String, Any> = mapOf(
      "newFailures" to newFailures,
      "newRunning" to newRunning,
      "newTasks" to newTasks,
      "newSucceeded" to newSucceeded,
      "stoppedFailing" to stoppedFailing,
      "finishedRunning" to finishedRunning
    )
  }
}
