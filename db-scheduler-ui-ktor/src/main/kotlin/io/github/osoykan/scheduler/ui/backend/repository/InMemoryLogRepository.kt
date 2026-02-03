package io.github.osoykan.scheduler.ui.backend.repository

import io.github.osoykan.scheduler.ui.backend.model.ExecutionLog
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

class InMemoryLogRepository(
  private val maxSize: Int
) : LogRepository {
  private val idGenerator = AtomicLong(0)
  private val logs = ConcurrentLinkedDeque<ExecutionEntry>()

  override fun add(entry: ExecutionLog) {
    val id = idGenerator.incrementAndGet()
    val log = ExecutionEntry(
      id = id,
      taskName = entry.taskName,
      taskInstance = entry.taskInstance,
      taskData = entry.taskData,
      pickedBy = entry.pickedBy,
      timeStarted = entry.timeStarted,
      timeFinished = entry.timeFinished,
      succeeded = entry.succeeded,
      durationMs = entry.durationMs,
      exceptionClass = entry.exceptionClass,
      exceptionMessage = entry.exceptionMessage,
      exceptionStackTrace = entry.exceptionStackTrace
    )

    logs.addFirst(log)

    // Trim old entries
    while (logs.size > maxSize) {
      logs.pollLast()
    }
  }

  override fun getAll(): List<ExecutionLog> = logs.map { entry ->
    ExecutionLog(
      id = entry.id,
      taskName = entry.taskName,
      taskInstance = entry.taskInstance,
      taskData = entry.taskData,
      pickedBy = entry.pickedBy,
      timeStarted = entry.timeStarted,
      timeFinished = entry.timeFinished,
      succeeded = entry.succeeded,
      durationMs = entry.durationMs,
      exceptionClass = entry.exceptionClass,
      exceptionMessage = entry.exceptionMessage,
      exceptionStackTrace = entry.exceptionStackTrace
    )
  }

  override fun count(): Int = logs.size

  override fun clear() = logs.clear()
}
