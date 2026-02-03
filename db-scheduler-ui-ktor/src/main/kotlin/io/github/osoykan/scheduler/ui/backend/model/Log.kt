package io.github.osoykan.scheduler.ui.backend.model

import java.time.Instant

/**
 * Request parameters for log queries.
 */
internal data class LogRequestParams(
  val filter: LogFilter = LogFilter.ALL,
  val pageNumber: Int = 0,
  val size: Int = 10,
  val sorting: LogSort = LogSort.DEFAULT,
  val isAsc: Boolean = false,
  val searchTermTaskName: String? = null,
  val searchTermTaskInstance: String? = null,
  val isTaskNameExactMatch: Boolean = false,
  val isTaskInstanceExactMatch: Boolean = false,
  val startTime: Instant? = null,
  val endTime: Instant? = null,
  val taskName: String? = null,
  val taskId: String? = null,
  val isRefresh: Boolean = true
) {
  enum class LogFilter { ALL, SUCCEEDED, FAILED }

  enum class LogSort { DEFAULT, TASK_NAME, TASK_INSTANCE, TIME_STARTED, TIME_FINISHED }
}
