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
 * Updated to match the exact UI contract expectations.
 */
internal class TaskService(
  private val scheduler: () -> Scheduler,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean
) {
  fun getAllTasks(params: TaskRequestParams): TasksResponse {
    val all = mutableListOf<ScheduledExecution<*>>()
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) { all += it }

    // Apply in-memory filters
    val filtered = all
      .asSequence()
      .filter { e ->
        when (params.filter) {
          TaskRequestParams.TaskFilter.SCHEDULED -> !e.isPicked
          TaskRequestParams.TaskFilter.RUNNING -> e.isPicked
          TaskRequestParams.TaskFilter.FAILED -> (e.consecutiveFailures ?: 0) > 0
          TaskRequestParams.TaskFilter.COMPLETED -> e.lastSuccess != null
          else -> true
        }
      }.filter { e ->
        params.searchTermTaskName?.takeIf { it.isNotBlank() }?.let { term ->
          val n = e.taskInstance.taskName
          if (params.isTaskNameExactMatch) n == term else n.contains(term, ignoreCase = true)
        } ?: true
      }.filter { e ->
        params.searchTermTaskInstance?.takeIf { it.isNotBlank() }?.let { term ->
          val i = e.taskInstance.id
          if (params.isTaskInstanceExactMatch) i == term else i.contains(term, ignoreCase = true)
        } ?: true
      }.filter { e -> params.startTime?.let { e.executionTime >= it } ?: true }
      .filter { e -> params.endTime?.let { e.executionTime <= it } ?: true }
      .toList()

    // Group by task name - this is the key difference!
    val groupedByTaskName = filtered.groupBy { it.taskInstance.taskName }

    // Convert each group to a Task object
    val tasks = groupedByTaskName.map { (taskName, executions) ->
      // Determine the overall state for this task group
      val hasRunning = executions.any { it.isPicked }

      Task(
        taskName = taskName,
        taskInstance = executions.map { it.taskInstance.id },
        taskData = if (taskData) executions.map { it.data } else executions.map { null },
        executionTime = executions.map { it.executionTime.toString() },
        picked = hasRunning, // true if any instance is currently running
        pickedBy = executions.map { it.pickedBy },
        lastSuccess = executions.map { it.lastSuccess?.toString() },
        lastFailure = executions.mapNotNull { it.lastFailure }.maxByOrNull { it }?.toString(),
        consecutiveFailures = executions.map { it.consecutiveFailures ?: 0 },
        lastHeartbeat = null, // Not available in ScheduledExecution
        version = 1 // Not available in ScheduledExecution, using default
      )
    }

    // Sort groups
    val sortedTasks = when (params.sorting) {
      TaskRequestParams.TaskSort.TASK_NAME -> tasks.sortedBy { it.taskName }
      else -> tasks.sortedBy { it.taskName } // Default sort by name
    }.let { if (!params.isAsc) it.reversed() else it }

    // Pagination
    val limit = params.size.coerceAtLeast(1)
    val page = params.pageNumber.coerceAtLeast(0)
    val from = (page * limit).coerceAtMost(sortedTasks.size)
    val to = (from + limit).coerceAtMost(sortedTasks.size)
    val pageItems = if (from < to) sortedTasks.subList(from, to) else emptyList()

    val totalItems = sortedTasks.size
    val totalPages = if (totalItems == 0) 0 else ((totalItems + limit - 1) / limit)

    return TasksResponse(
      items = pageItems,
      numberOfItems = totalItems,
      numberOfPages = totalPages
    )
  }

  // getTask should return TasksResponse (same as getAllTasks), filtered by specific task
  fun getTask(params: TaskDetailsRequestParams): TasksResponse {
    val name = params.taskName
    val id = params.taskId

    val all = mutableListOf<ScheduledExecution<*>>()
    scheduler().fetchScheduledExecutions(ScheduledExecutionsFilter.all()) {
      if ((name == null || it.taskInstance.taskName == name) &&
        (id == null || it.taskInstance.id == id)
      ) {
        all += it
      }
    }

    if (all.isEmpty()) {
      return TasksResponse(items = emptyList(), numberOfItems = 0, numberOfPages = 0)
    }

    // Group by task name even for details
    val groupedByTaskName = all.groupBy { it.taskInstance.taskName }
    val tasks = groupedByTaskName.map { (taskName, executions) ->
      Task(
        taskName = taskName,
        taskInstance = executions.map { it.taskInstance.id },
        taskData = if (taskData) executions.map { it.data } else executions.map { null },
        executionTime = executions.map { it.executionTime.toString() },
        picked = executions.any { it.isPicked },
        pickedBy = executions.map { it.pickedBy },
        lastSuccess = executions.map { it.lastSuccess?.toString() },
        lastFailure = executions.mapNotNull { it.lastFailure }.maxByOrNull { it }?.toString(),
        consecutiveFailures = executions.map { it.consecutiveFailures ?: 0 },
        lastHeartbeat = null, // Not available in ScheduledExecution
        version = 1 // Not available in ScheduledExecution, using default
      )
    }

    return TasksResponse(
      items = tasks,
      numberOfItems = tasks.size,
      numberOfPages = 1
    )
  }

  fun pollTasks(params: TaskDetailsRequestParams): PollResponse {
    val cacheKey = "poll_${params.hashCode()}"

    // Get current state
    val currentTasks = getAllTasksInternal(params)
    val currentTaskStates = currentTasks.associate { task ->
      task.taskName to TaskState(
        isRunning = task.picked,
        hasFailed = task.lastFailure != null,
        hasSucceeded = task.lastSuccess.any { it != null },
        consecutiveFailures = task.consecutiveFailures.maxOrNull() ?: 0
      )
    }

    // Get previous state from cache (if it exists)
    val pollState = caching.get(cacheKey) {
      PollState(
        previousTasks = emptyMap(),
        currentTasks = emptyMap()
      )
    } as PollState

    // Use the currentTasks from the cache as our previousTasks for comparison
    val previousTaskStates = pollState.currentTasks

    // Calculate changes between previous and current
    val changes = calculateTaskChanges(previousTaskStates, currentTaskStates)

    // Update cache with new state (invalidate first to force refresh)
    caching.invalidate(cacheKey)
    caching.get(cacheKey) {
      PollState(
        previousTasks = previousTaskStates, // This won't be used next time anyway
        currentTasks = currentTaskStates // This becomes previousTasks in next call
      )
    }

    return changes
  }

  private fun getAllTasksInternal(params: TaskDetailsRequestParams): List<Task> {
    // Convert TaskDetailsRequestParams to TaskRequestParams for reuse
    val taskParams = TaskRequestParams(
      filter = params.filter,
      pageNumber = params.pageNumber,
      size = params.size ?: 1000, // Use large default for polling
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
    return getAllTasks(taskParams).items
  }

  private fun calculateTaskChanges(previous: Map<String, TaskState>, current: Map<String, TaskState>): PollResponse {
    var newFailures = 0
    var newRunning = 0
    var newTasks = 0
    var newSucceeded = 0
    var stoppedFailing = 0
    var finishedRunning = 0

    // Check for changes in existing tasks
    for ((taskName, currentState) in current) {
      val previousState = previous[taskName]

      if (previousState == null) {
        // New task
        newTasks++
        if (currentState.isRunning) newRunning++
        if (currentState.hasFailed && currentState.consecutiveFailures > 0) newFailures++
        if (currentState.hasSucceeded) newSucceeded++
      } else {
        // Existing task - check for state changes

        // New failures: task started failing OR increased consecutive failures
        if (currentState.consecutiveFailures > previousState.consecutiveFailures) {
          newFailures++
        }

        // Stopped failing: had failures before, now has none
        if (previousState.consecutiveFailures > 0 && currentState.consecutiveFailures == 0) {
          stoppedFailing++
        }

        // New running: wasn't running before, is running now
        if (!previousState.isRunning && currentState.isRunning) {
          newRunning++
        }

        // Finished running: was running before, not running now
        if (previousState.isRunning && !currentState.isRunning) {
          finishedRunning++
        }

        // New succeeded: wasn't succeeded before, is succeeded now
        if (!previousState.hasSucceeded && currentState.hasSucceeded) {
          newSucceeded++
        }
      }
    }

    return PollResponse(
      newFailures = newFailures,
      newRunning = newRunning,
      newTasks = newTasks,
      newSucceeded = newSucceeded,
      stoppedFailing = stoppedFailing,
      finishedRunning = finishedRunning
    )
  }

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

  fun runTaskNow(id: String, name: String, scheduleTime: Instant): TaskActionResponse {
    val instance = TaskInstanceId.of(name, id)
    return try {
      scheduler().reschedule(instance, scheduleTime)
      TaskActionResponse(
        status = "accepted",
        id = id,
        name = name,
        scheduleTime = scheduleTime.toString()
      )
    } catch (_: Exception) {
      TaskActionResponse(
        status = "not_found",
        id = id,
        name = name,
        scheduleTime = scheduleTime.toString()
      )
    }
  }

  fun runTaskGroupNow(groupName: String, onlyFailed: Boolean): TaskActionResponse {
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
    return TaskActionResponse(
      status = if (updated > 0) "accepted" else "not_found",
      updated = updated
    )
  }

  fun deleteTask(id: String, name: String): TaskActionResponse {
    val instance = TaskInstanceId.of(name, id)
    return try {
      scheduler().cancel(instance)
      TaskActionResponse(
        status = "deleted",
        id = id,
        name = name
      )
    } catch (_: Exception) {
      TaskActionResponse(
        status = "not_found",
        id = id,
        name = name
      )
    }
  }
}
