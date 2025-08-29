package io.github.osoykan.scheduler.ui.backend.service

import com.github.kagkarlsson.scheduler.Scheduler
import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.model.TaskRequestParams
import io.github.osoykan.scheduler.ui.backend.util.Caching
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

/**
 * Pure-Kotlin replacement for the original TaskLogic using JDBC + db-scheduler schema.
 */
internal class TaskService(
  private val scheduler: () -> Scheduler,
  private val dataSource: () -> DataSource,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean
) {
  fun getAllTasks(params: TaskRequestParams): Any {
    val (where, args) = buildWhereClause(params)
    val sort = buildSortClause(params)
    val limit = params.size.coerceAtLeast(1)
    val offset = (params.pageNumber.coerceAtLeast(0)) * limit

    dataSource().connection.use { conn ->
      // Count
      val countSql = "SELECT COUNT(*) FROM scheduled_tasks $where"
      val total = conn.prepareStatement(countSql).use { ps ->
        bind(ps, args)
        ps.executeQuery().use { rs ->
          rs.next()
          rs.getLong(1)
        }
      }

      // Page
      val pageSql = """
        SELECT task_name, task_instance, execution_time, picked, picked_by,
               last_success, last_failure, consecutive_failures, last_heartbeat, version
        FROM scheduled_tasks
        $where $sort LIMIT ? OFFSET ?
      """.trimIndent()
      val pageArgs = args + listOf(limit, offset)
      val content = conn.prepareStatement(pageSql).use { ps ->
        bind(ps, pageArgs)
        ps.executeQuery().use { rs ->
          val list = mutableListOf<Map<String, Any?>>()
          while (rs.next()) {
            list += mapRow(rs)
          }
          list
        }
      }

      val totalPages = if (total == 0L) 0 else ((total + limit - 1) / limit).toInt()
      return mapOf(
        "content" to content,
        "pageNumber" to params.pageNumber,
        "size" to params.size,
        "totalElements" to total,
        "totalPages" to totalPages
      )
    }
  }

  fun getTask(params: TaskDetailsRequestParams): Any {
    val name = requireNotNull(params.taskName) { "taskName is required" }
    val id = requireNotNull(params.taskId) { "taskId is required" }
    dataSource().connection.use { conn ->
      val sql = """
        SELECT task_name, task_instance, execution_time, picked, picked_by,
               last_success, last_failure, consecutive_failures, last_heartbeat, version
        FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?
      """.trimIndent()
      val task = conn.prepareStatement(sql).use { ps ->
        ps.setString(1, name)
        ps.setString(2, id)
        ps.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
      }
      return mapOf(
        "task" to (task ?: emptyMap<String, Any?>()),
        "executions" to emptyList<Any>() // db-scheduler keeps single row per future execution
      )
    }
  }

  fun pollTasks(params: TaskDetailsRequestParams): Any {
    // Simple stub: always return not updated. Could be improved with last_heartbeat/execution_time windows.
    return mapOf(
      "updated" to false,
      "content" to emptyList<Any>()
    )
  }

  fun runTaskNow(id: String, name: String, scheduleTime: Instant): Any {
    val rows = dataSource().connection.use { conn ->
      val sql = """
        UPDATE scheduled_tasks
        SET execution_time = ?, picked = FALSE, version = version + 1
        WHERE task_name = ? AND task_instance = ?
      """.trimIndent()
      conn.prepareStatement(sql).use { ps ->
        ps.setObject(1, scheduleTime)
        ps.setString(2, name)
        ps.setString(3, id)
        ps.executeUpdate()
      }
    }
    return mapOf("status" to if (rows > 0) "accepted" else "not_found", "id" to id, "name" to name, "scheduleTime" to scheduleTime.toString())
  }

  fun runTaskGroupNow(groupName: String, onlyFailed: Boolean): Any {
    val rows = dataSource().connection.use { conn ->
      val sql = buildString {
        append("UPDATE scheduled_tasks SET execution_time = NOW(), picked = FALSE, version = version + 1 WHERE task_name = ?")
        if (onlyFailed) append(" AND consecutive_failures IS NOT NULL AND consecutive_failures > 0")
      }
      conn.prepareStatement(sql).use { ps ->
        ps.setString(1, groupName)
        ps.executeUpdate()
      }
    }
    return mapOf("status" to if (rows > 0) "accepted" else "not_found", "updated" to rows)
  }

  fun deleteTask(id: String, name: String): Any {
    val rows = dataSource().connection.use { conn ->
      val sql = "DELETE FROM scheduled_tasks WHERE task_name = ? AND task_instance = ?"
      conn.prepareStatement(sql).use { ps ->
        ps.setString(1, name)
        ps.setString(2, id)
        ps.executeUpdate()
      }
    }
    return mapOf("status" to if (rows > 0) "deleted" else "not_found", "id" to id, "name" to name)
  }

  private fun mapRow(rs: ResultSet): Map<String, Any?> = mapOf(
    "taskName" to rs.getString("task_name"),
    "taskInstance" to rs.getString("task_instance"),
    "executionTime" to rs.getTimestamp("execution_time")?.toInstant()?.toString(),
    "picked" to rs.getBoolean("picked"),
    "pickedBy" to rs.getString("picked_by"),
    "lastSuccess" to rs.getTimestamp("last_success")?.toInstant()?.toString(),
    "lastFailure" to rs.getTimestamp("last_failure")?.toInstant()?.toString(),
    "consecutiveFailures" to (rs.getObject("consecutive_failures") as? Number)?.toInt(),
    "lastHeartbeat" to rs.getTimestamp("last_heartbeat")?.toInstant()?.toString(),
    "version" to (rs.getObject("version") as? Number)?.toLong()
  )

  private fun buildWhereClause(params: TaskRequestParams): Pair<String, List<Any?>> {
    val conditions = mutableListOf<String>()
    val args = mutableListOf<Any?>()

    // Filters
    when (params.filter) {
      TaskRequestParams.TaskFilter.SCHEDULED -> conditions += "picked = FALSE"
      TaskRequestParams.TaskFilter.RUNNING -> conditions += "picked = TRUE"
      TaskRequestParams.TaskFilter.FAILED -> conditions += "consecutive_failures IS NOT NULL AND consecutive_failures > 0"
      TaskRequestParams.TaskFilter.COMPLETED -> conditions += "last_success IS NOT NULL"
      else -> {}
    }

    // Search
    params.searchTermTaskName?.takeIf { it.isNotBlank() }?.let {
      if (params.isTaskNameExactMatch) {
        conditions += "task_name = ?"; args += it
      } else {
        conditions += "task_name ILIKE ?"; args += "%$it%"
      }
    }
    params.searchTermTaskInstance?.takeIf { it.isNotBlank() }?.let {
      if (params.isTaskInstanceExactMatch) {
        conditions += "task_instance = ?"; args += it
      } else {
        conditions += "task_instance ILIKE ?"; args += "%$it%"
      }
    }

    // Time window
    params.startTime?.let { conditions += "execution_time >= ?"; args += it }
    params.endTime?.let { conditions += "execution_time <= ?"; args += it }

    val where = if (conditions.isEmpty()) "" else ("WHERE " + conditions.joinToString(" AND "))
    return where to args
  }

  private fun buildSortClause(params: TaskRequestParams): String {
    val column = when (params.sorting) {
      TaskRequestParams.TaskSort.TASK_NAME -> "task_name"
      TaskRequestParams.TaskSort.TASK_INSTANCE -> "task_instance"
      TaskRequestParams.TaskSort.START_TIME -> "execution_time"
      TaskRequestParams.TaskSort.END_TIME -> "last_success"
      else -> "execution_time"
    }
    val dir = if (params.isAsc) "ASC" else "DESC"
    return "ORDER BY $column $dir"
  }

  private fun bind(ps: java.sql.PreparedStatement, args: List<Any?>) {
    var i = 1
    for (a in args) {
      when (a) {
        is Instant -> ps.setObject(i, a)
        is Number -> ps.setObject(i, a)
        is Boolean -> ps.setBoolean(i, a)
        else -> ps.setObject(i, a)
      }
      i++
    }
  }
}
