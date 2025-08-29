package io.github.osoykan.scheduler.ui.backend.service

import io.github.osoykan.scheduler.ui.backend.model.TaskDetailsRequestParams
import io.github.osoykan.scheduler.ui.backend.util.Caching
import javax.sql.DataSource

/**
 * Stub pure-Kotlin replacement for the original LogLogic. Not yet wired because history is disabled.
 */
internal class LogService(
  private val dataSource: () -> DataSource,
  private val caching: Caching<String, Any>,
  private val taskData: Boolean,
  private val logTableName: String,
  private val logLimit: Int
) {
  fun getLogs(params: TaskDetailsRequestParams): Any {
    // TODO: Implement JDBC querying from logTableName
    return emptyList<Any>()
  }

  fun pollLogs(params: TaskDetailsRequestParams): Any {
    // TODO: Implement polling logic
    return mapOf("updated" to false, "logs" to emptyList<Any>())
  }
}
