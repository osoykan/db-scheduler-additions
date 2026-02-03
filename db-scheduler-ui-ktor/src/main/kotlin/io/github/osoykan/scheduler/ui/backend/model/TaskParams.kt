package io.github.osoykan.scheduler.ui.backend.model

import java.time.Instant

/**
 * Internal DTOs mirroring the db-scheduler-ui request/response schema.
 *
 * REQUEST PARAMS (TaskRequestParams, TaskDetailsRequestParams): Still used for parsing incoming query parameters.
 *
 * RESPONSE DTOs (TasksResponse, Task, ConfigResponse, etc.): DEPRECATED - now using Map<String, Any> instead.
 * This avoids serialization configuration dependencies (no need for @Serializable annotations or Jackson modules).
 * Users can work with any serialization library without additional setup.
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
