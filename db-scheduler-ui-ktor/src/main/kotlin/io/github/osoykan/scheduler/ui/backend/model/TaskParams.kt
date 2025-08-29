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
