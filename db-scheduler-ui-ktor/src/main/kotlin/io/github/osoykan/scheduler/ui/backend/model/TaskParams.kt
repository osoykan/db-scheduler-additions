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

/**
 * API responses now use Map<String, Any> to avoid serialization configuration dependencies.
 * This allows users to work with any serialization library (Jackson, kotlinx.serialization, etc.)
 * without needing to configure annotations or modules.
 *
 * Response schemas:
 * - Config: mapOf("historyEnabled" to Boolean, "configured" to Boolean)
 * - Tasks: mapOf("items" to List<Map<String,Any>>, "numberOfItems" to Int, "numberOfPages" to Int)
 * - Task: mapOf("taskName" to String, "taskInstance" to List<String>, "taskData" to List<Any?>, ...)
 * - TaskAction: mapOf("status" to String, "id" to String?, "name" to String?, "scheduleTime" to String?, "updated" to Int?)
 * - Poll: mapOf("newFailures" to Int, "newRunning" to Int, "newTasks" to Int, "newSucceeded" to Int, "stoppedFailing" to Int, "finishedRunning" to Int)
 * - Log: mapOf("items" to List<Map<String,Any>>, "numberOfItems" to Int, "numberOfPages" to Int)
 */
