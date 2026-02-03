package io.github.osoykan.scheduler.ui.backend.repository

import io.github.osoykan.scheduler.ui.backend.model.ExecutionLog

interface LogRepository {
  fun add(entry: ExecutionLog)

  fun getAll(): List<ExecutionLog>

  fun count(): Int

  fun clear()
}
