package io.github.osoykan.scheduler.ui.backend.model

import java.time.Instant

/**
 * Internal DTOs mirroring the db-scheduler-ui request/response schema.
 * Kept simple to be compatible with different serialization engines used by host Ktor app.
 */

internal data class TaskRequestParams(
  val filter: TaskFilter = TaskFilter.ALL,
  val pageNumber: Int = 0,
  val size: Int = 10,
  val sorting: TaskSort = TaskSort.DEFAULT,
  val isAsc: Boolean = true,
  val searchTermTaskName: String? = null,
  val searchTermTaskInstance: String? = null,
  val isTaskNameExactMatch: Boolean = false,
  val isTaskInstanceExactMatch: Boolean = false,
  val startTime: Instant? = null,
  val endTime: Instant? = null,
  val isRefresh: Boolean = true
) {
  enum class TaskFilter { ALL, SCHEDULED, FAILED, COMPLETED, RUNNING }

  enum class TaskSort { DEFAULT, TASK_NAME, TASK_INSTANCE, START_TIME, END_TIME }
}

/**
 * Extends TaskRequestParams with details context.
 */
internal data class TaskDetailsRequestParams(
  val filter: TaskRequestParams.TaskFilter = TaskRequestParams.TaskFilter.ALL,
  val pageNumber: Int = 0,
  val size: Int = 10,
  val sorting: TaskRequestParams.TaskSort = TaskRequestParams.TaskSort.DEFAULT,
  val isAsc: Boolean = true,
  val searchTermTaskName: String? = null,
  val searchTermTaskInstance: String? = null,
  val isTaskNameExactMatch: Boolean = false,
  val isTaskInstanceExactMatch: Boolean = false,
  val startTime: Instant? = null,
  val endTime: Instant? = null,
  val taskName: String? = null,
  val taskId: String? = null,
  val isRefresh: Boolean = true
)

/**
 * Minimal config payload used by the UI frontend at /db-scheduler-api/config
 */
internal data class ConfigResponse(
  val historyEnabled: Boolean,
  val configured: Boolean
)

/**
 * Response DTOs matching the exact JSON schema expected by the db-scheduler-ui frontend
 * Tasks are grouped by taskName, with arrays of instances/executions
 */
internal data class TasksResponse(
  val items: List<Task>,
  val numberOfItems: Int,
  val numberOfPages: Int
)

internal data class Task(
  val taskName: String,
  val taskInstance: List<String>,
  val taskData: List<Any?>,
  val executionTime: List<String>,
  val picked: Boolean,
  val pickedBy: List<String?>,
  val lastSuccess: List<String?>,
  val lastFailure: String?,
  val consecutiveFailures: List<Int>,
  val lastHeartbeat: String?,
  val version: Int
)

internal data class TaskActionResponse(
  val status: String,
  val id: String? = null,
  val name: String? = null,
  val scheduleTime: String? = null,
  val updated: Int? = null
)

// Poll response shows counts of state changes
internal data class PollResponse(
  val newFailures: Int,
  val newRunning: Int,
  val newTasks: Int,
  val newSucceeded: Int = 0,
  val stoppedFailing: Int,
  val finishedRunning: Int
)

// Log response uses the InfiniteScrollResponse pattern
internal data class LogResponse(
  val items: List<Log>,
  val numberOfItems: Int,
  val numberOfPages: Int
)

internal data class Log(
  val id: Int,
  val taskName: String,
  val taskInstance: String,
  val taskData: Any?,
  val pickedBy: String?,
  val timeStarted: String,
  val timeFinished: String,
  val succeeded: Boolean,
  val durationMs: Long,
  val exceptionClass: String?,
  val exceptionMessage: String?,
  val exceptionStackTrace: String?
)
