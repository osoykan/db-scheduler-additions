package io.github.osoykan.scheduler.ui.backend.service

import io.github.osoykan.scheduler.ui.backend.model.ExecutionLog
import io.github.osoykan.scheduler.ui.backend.model.LogRequestParams
import io.github.osoykan.scheduler.ui.backend.repository.LogRepository
import io.github.osoykan.scheduler.ui.backend.util.Caching
import java.time.Instant

internal class LogService(
  private val logRepository: LogRepository,
  private val caching: Caching<String, Any>
) {
  fun getAllLogs(params: LogRequestParams): Map<String, Any> {
    val logs = logRepository.getAll()
    val filtered = applyFilters(logs, params)
    val sorted = sortLogs(filtered, params.sorting, params.isAsc)
    return paginateLogs(sorted, params.pageNumber, params.size)
  }

  fun pollLogs(params: LogRequestParams): Map<String, Any> {
    val cacheKey = buildPollCacheKey(params)
    val currentLogs = logRepository.getAll()
    val filteredLogs = applyFilters(currentLogs, params)
    val currentStates = extractLogStates(filteredLogs)
    val previousStates = getPreviousStates(cacheKey)
    val changes = calculateLogChanges(previousStates, currentStates)

    updatePollCache(cacheKey, currentStates)

    return changes
  }

  private fun applyFilters(
    logs: List<ExecutionLog>,
    params: LogRequestParams
  ): List<ExecutionLog> = logs
    .asSequence()
    .filter { applyStatusFilter(it, params.filter) }
    .filter { applyTaskNameFilter(it, params.searchTermTaskName, params.taskName, params.isTaskNameExactMatch) }
    .filter { applyTaskInstanceFilter(it, params.searchTermTaskInstance, params.taskId, params.isTaskInstanceExactMatch) }
    .filter { applyTimeRangeFilter(it, params.startTime, params.endTime) }
    .toList()

  private fun applyStatusFilter(log: ExecutionLog, filter: LogRequestParams.LogFilter): Boolean = when (filter) {
    LogRequestParams.LogFilter.SUCCEEDED -> log.succeeded
    LogRequestParams.LogFilter.FAILED -> !log.succeeded
    LogRequestParams.LogFilter.ALL -> true
  }

  private fun applyTaskNameFilter(
    log: ExecutionLog,
    searchTerm: String?,
    taskName: String?,
    exactMatch: Boolean
  ): Boolean {
    val term = taskName ?: searchTerm?.takeIf { it.isNotBlank() } ?: return true
    return if (exactMatch) log.taskName == term else log.taskName.contains(term, ignoreCase = true)
  }

  private fun applyTaskInstanceFilter(
    log: ExecutionLog,
    searchTerm: String?,
    taskId: String?,
    exactMatch: Boolean
  ): Boolean {
    val term = taskId ?: searchTerm?.takeIf { it.isNotBlank() } ?: return true
    return if (exactMatch) log.taskInstance == term else log.taskInstance.contains(term, ignoreCase = true)
  }

  private fun applyTimeRangeFilter(log: ExecutionLog, startTime: Instant?, endTime: Instant?): Boolean {
    val matchesStart = startTime?.let { log.timeFinished >= it } ?: true
    val matchesEnd = endTime?.let { log.timeFinished <= it } ?: true
    return matchesStart && matchesEnd
  }

  private fun sortLogs(
    logs: List<ExecutionLog>,
    sorting: LogRequestParams.LogSort,
    ascending: Boolean
  ): List<ExecutionLog> {
    val sorted = when (sorting) {
      LogRequestParams.LogSort.TASK_NAME -> logs.sortedBy { it.taskName }
      LogRequestParams.LogSort.TASK_INSTANCE -> logs.sortedBy { it.taskInstance }
      LogRequestParams.LogSort.TIME_STARTED -> logs.sortedBy { it.timeStarted }
      LogRequestParams.LogSort.TIME_FINISHED -> logs.sortedBy { it.timeFinished }
      LogRequestParams.LogSort.DEFAULT -> logs.sortedByDescending { it.timeFinished } // newest first
    }
    return if (ascending &&
      sorting != LogRequestParams.LogSort.DEFAULT
    ) {
      sorted
    } else if (sorting ==
      LogRequestParams.LogSort.DEFAULT
    ) {
      sorted
    } else {
      sorted.reversed()
    }
  }

  private fun paginateLogs(logs: List<ExecutionLog>, pageNumber: Int, pageSize: Int): Map<String, Any> {
    val limit = pageSize.coerceAtLeast(1)
    val page = pageNumber.coerceAtLeast(0)
    val from = (page * limit).coerceAtMost(logs.size)
    val to = (from + limit).coerceAtMost(logs.size)
    val pageItems = if (from < to) logs.subList(from, to) else emptyList()
    val totalPages = if (logs.isEmpty()) 0 else ((logs.size + limit - 1) / limit)

    return buildLogsResponse(pageItems.map { it.toMap() }, logs.size, totalPages)
  }

  private data class LogState(
    val successCount: Int,
    val failureCount: Int,
    val latestId: Long
  )

  private data class PollState(
    val previousState: LogState,
    val currentState: LogState
  )

  private fun extractLogStates(logs: List<ExecutionLog>): LogState = LogState(
    successCount = logs.count { it.succeeded },
    failureCount = logs.count { !it.succeeded },
    latestId = logs.maxOfOrNull { it.id } ?: 0
  )

  private fun getPreviousStates(cacheKey: String): LogState {
    val pollState = caching.get(cacheKey) {
      PollState(
        previousState = LogState(0, 0, 0),
        currentState = LogState(0, 0, 0)
      )
    } as PollState
    return pollState.currentState
  }

  private fun updatePollCache(cacheKey: String, currentState: LogState) {
    caching.invalidate(cacheKey)
    caching.get(cacheKey) {
      PollState(previousState = LogState(0, 0, 0), currentState = currentState)
    }
  }

  private fun buildPollCacheKey(params: LogRequestParams): String = "log_poll_${params.hashCode()}"

  private fun calculateLogChanges(previous: LogState, current: LogState): Map<String, Any> {
    val newSucceeded = (current.successCount - previous.successCount).coerceAtLeast(0)
    val newFailed = (current.failureCount - previous.failureCount).coerceAtLeast(0)

    return mapOf(
      "newFailures" to newFailed,
      "newRunning" to 0,
      "newTasks" to (newSucceeded + newFailed),
      "newSucceeded" to newSucceeded,
      "stoppedFailing" to 0,
      "finishedRunning" to 0
    )
  }

  private fun buildLogsResponse(
    items: List<Map<String, Any?>>,
    totalItems: Int = items.size,
    totalPages: Int = 1
  ): Map<String, Any> = mapOf(
    "items" to items,
    "numberOfItems" to totalItems,
    "numberOfPages" to totalPages
  )

  private fun ExecutionLog.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "taskName" to taskName,
    "taskInstance" to taskInstance,
    "taskData" to taskData,
    "pickedBy" to pickedBy,
    "timeStarted" to timeStarted.toString(),
    "timeFinished" to timeFinished.toString(),
    "succeeded" to succeeded,
    "durationMs" to durationMs,
    "exceptionClass" to exceptionClass,
    "exceptionMessage" to exceptionMessage,
    "exceptionStackTrace" to exceptionStackTrace
  )
}
