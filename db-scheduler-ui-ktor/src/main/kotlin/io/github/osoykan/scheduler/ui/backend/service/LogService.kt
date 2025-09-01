package io.github.osoykan.scheduler.ui.backend.service

import com.github.kagkarlsson.scheduler.Scheduler
import io.github.osoykan.scheduler.ui.backend.model.*
import io.github.osoykan.scheduler.ui.backend.util.Caching
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Pure-Kotlin replacement for LogLogic.
 * Tries to use Scheduler APIs first, falls back to log table queries only when necessary.
 * Note: db-scheduler doesn't have built-in logging APIs, so this implementation
 * focuses on execution history derived from scheduler state.
 */
internal class LogService(
  private val scheduler: () -> Scheduler,
  private val dataSource: () -> DataSource,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean,
  private val logTableName: String,
  private val logLimit: Int
) {
  fun getLogs(params: TaskDetailsRequestParams): LogResponse {
    val name = params.taskName
    val id = params.taskId

    return try {
      // Primary approach: try to derive log-like information from scheduler executions
      val logs = getLogsFromSchedulerExecutions(name, id)
      if (logs.isNotEmpty()) {
        LogResponse(logs, logs.size, 1)
      } else {
        // Fallback: use log table if available (only when scheduler APIs don't provide enough info)
        getLogsFromLogTable(name, id)
      }
    } catch (e: Exception) {
      // If all else fails, return empty logs rather than crashing
      LogResponse(emptyList(), 0, 0)
    }
  }

  private fun getLogsFromSchedulerExecutions(name: String?, id: String?): List<Log> {
    // Use scheduler API to get execution information and derive log-like entries
    val logs = mutableListOf<Log>()
    var logId = 1

    try {
      scheduler().fetchScheduledExecutions(
        com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter
          .all()
      ) { execution ->
        if ((name == null || execution.taskInstance.taskName == name) &&
          (id == null || execution.taskInstance.id == id)
        ) {
          // Create log entries from execution state
          if (execution.lastSuccess != null) {
            logs.add(
              Log(
                id = logId++,
                taskName = execution.taskInstance.taskName,
                taskInstance = execution.taskInstance.id,
                taskData = if (taskData) execution.data else null,
                pickedBy = execution.pickedBy,
                timeStarted = execution.lastSuccess.toString(),
                timeFinished = execution.lastSuccess.toString(),
                succeeded = true,
                durationMs = 0, // Unknown from scheduler state
                exceptionClass = null,
                exceptionMessage = null,
                exceptionStackTrace = null
              )
            )
          }

          if (execution.lastFailure != null) {
            logs.add(
              Log(
                id = logId++,
                taskName = execution.taskInstance.taskName,
                taskInstance = execution.taskInstance.id,
                taskData = if (taskData) execution.data else null,
                pickedBy = execution.pickedBy,
                timeStarted = execution.lastFailure.toString(),
                timeFinished = execution.lastFailure.toString(),
                succeeded = false,
                durationMs = 0, // Unknown from scheduler state
                exceptionClass = "UnknownException",
                exceptionMessage = "Task execution failed (${execution.consecutiveFailures} consecutive failures)",
                exceptionStackTrace = null
              )
            )
          }
        }
      }
    } catch (e: Exception) {
      // If scheduler API fails, fall back to log table
      return emptyList()
    }

    // Sort by timeStarted (most recent first) and limit
    val limit = if (logLimit > 0) logLimit else 200
    return logs.sortedByDescending { it.timeStarted }.take(limit)
  }

  private fun getLogsFromLogTable(name: String?, id: String?): LogResponse {
    // Fallback to direct SQL - only used when scheduler APIs don't provide enough information
    return try {
      dataSource().connection.use { conn ->
        val base = "SELECT id, task_name, task_instance, task_data, picked_by, time_started, time_finished, " +
          "succeeded, duration_ms, exception_class, exception_message, exception_stack_trace FROM $logTableName"
        val (where, args) = when {
          name != null && id != null -> " WHERE task_name = ? AND task_instance = ?" to listOf(name, id)
          name != null -> " WHERE task_name = ?" to listOf(name)
          else -> "" to emptyList()
        }
        val limit = if (logLimit > 0) logLimit else 200
        val sql = "$base$where ORDER BY time_started DESC LIMIT ?"
        val items = conn.prepareStatement(sql).use { ps ->
          var i = 1
          for (a in args) ps.setObject(i++, a)
          ps.setInt(i, limit)
          ps.executeQuery().use { rs ->
            val list = mutableListOf<Log>()
            while (rs.next()) list += mapLogRow(rs)
            list
          }
        }
        LogResponse(items, items.size, if (items.size <= limit) 1 else 2)
      }
    } catch (e: Exception) {
      LogResponse(emptyList(), 0, 0)
    }
  }

  fun pollLogs(params: TaskDetailsRequestParams): LogResponse {
    // Simple stub implementation - in a real system, this could check for updates since last poll
    return LogResponse(emptyList(), 0, 0)
  }

  private fun mapLogRow(rs: ResultSet): Log = Log(
    id = rs.getInt("id"),
    taskName = rs.getString("task_name"),
    taskInstance = rs.getString("task_instance"),
    taskData = rs.getObject("task_data"),
    pickedBy = rs.getString("picked_by"),
    timeStarted = rs.getTimestamp("time_started")?.toInstant()?.toString() ?: "",
    timeFinished = rs.getTimestamp("time_finished")?.toInstant()?.toString() ?: "",
    succeeded = rs.getBoolean("succeeded"),
    durationMs = rs.getLong("duration_ms"),
    exceptionClass = rs.getString("exception_class"),
    exceptionMessage = rs.getString("exception_message"),
    exceptionStackTrace = rs.getString("exception_stack_trace")
  )
}
