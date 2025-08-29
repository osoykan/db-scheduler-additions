package io.github.osoykan.scheduler.ui.backend.service

import com.github.kagkarlsson.scheduler.Scheduler
import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.model.TaskRequestParams
import io.github.osoykan.scheduler.ui.backend.util.Caching
import java.time.Instant

/**
 * Minimal pure-Kotlin replacement for the original TaskLogic.
 * For now it returns placeholder payloads that are structurally simple JSON objects.
 * This unblocks removal of the Spring-based db-scheduler-ui dependency.
 */
internal class TaskService(
  private val scheduler: () -> Scheduler,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean
) {
  fun getAllTasks(params: TaskRequestParams): Any {
    // TODO: Implement real querying using DataSource/JDBC and/or scheduler APIs
    return mapOf(
      "content" to emptyList<Any>(),
      "pageNumber" to params.pageNumber,
      "size" to params.size,
      "totalElements" to 0,
      "totalPages" to 0
    )
  }

  fun getTask(params: TaskDetailsRequestParams): Any {
    // TODO: Implement actual details retrieval
    return mapOf(
      "task" to mapOf(
        "name" to (params.taskName ?: ""),
        "id" to (params.taskId ?: "")
      ),
      "executions" to emptyList<Any>()
    )
  }

  fun pollTasks(params: TaskDetailsRequestParams): Any {
    // TODO: Implement polling logic
    return mapOf(
      "updated" to false,
      "content" to emptyList<Any>()
    )
  }

  fun runTaskNow(id: String, name: String, scheduleTime: Instant): Any {
    // TODO: Implement rerun via scheduler API
    return mapOf("status" to "accepted", "id" to id, "name" to name, "scheduleTime" to scheduleTime.toString())
  }

  fun runTaskGroupNow(groupName: String, onlyFailed: Boolean): Any {
    // TODO: Implement rerun group via scheduler API
    return mapOf("status" to "accepted", "groupName" to groupName, "onlyFailed" to onlyFailed)
  }

  fun deleteTask(id: String, name: String): Any {
    // TODO: Implement delete via scheduler API or direct SQL
    return mapOf("status" to "accepted", "id" to id, "name" to name)
  }
}
