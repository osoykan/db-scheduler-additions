package io.github.osoykan.scheduler.ui.backend.service

import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.util.Caching
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Pure-Kotlin replacement for LogLogic, reading from a log table if enabled.
 */
internal class LogService(
  private val dataSource: () -> DataSource,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean,
  private val logTableName: String,
  private val logLimit: Int
) {
  fun getLogs(params: TaskDetailsRequestParams): Any {
    val name = params.taskName
    val id = params.taskId
    dataSource().connection.use { conn ->
      val base = "SELECT occurred_at, level, message, task_name, task_instance FROM $logTableName"
      val (where, args) = when {
        name != null && id != null -> " WHERE task_name = ? AND task_instance = ?" to listOf(name, id)
        name != null -> " WHERE task_name = ?" to listOf(name)
        else -> "" to emptyList()
      }
      val limit = if (logLimit > 0) logLimit else 200
      val sql = "$base$where ORDER BY occurred_at DESC LIMIT ?"
      val items = conn.prepareStatement(sql).use { ps ->
        var i = 1
        for (a in args) ps.setObject(i++, a)
        ps.setInt(i, limit)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<Map<String, Any?>>()
          while (rs.next()) list += mapLogRow(rs)
          list
        }
      }
      return mapOf("logs" to items)
    }
  }

  fun pollLogs(params: TaskDetailsRequestParams): Any {
    // Simple stub; frontend will still poll periodically
    return mapOf("updated" to false, "logs" to emptyList<Any>())
  }

  private fun mapLogRow(rs: ResultSet): Map<String, Any?> = mapOf(
    "occurredAt" to rs.getTimestamp("occurred_at")?.toInstant()?.toString(),
    "level" to rs.getString("level"),
    "message" to rs.getString("message"),
    "taskName" to rs.getString("task_name"),
    "taskInstance" to rs.getString("task_instance"),
  )
}
