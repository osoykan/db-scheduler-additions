package io.github.osoykan.scheduler.ui.backend.repository

import io.github.osoykan.scheduler.ui.backend.model.ExecutionLog
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

class LogRepository(
  private val maxSize: Int = 10000
) {
  private val idGenerator = AtomicLong(0)
  private val logs = ConcurrentLinkedDeque<ExecutionLog>()

  /**
   * Adds a new execution log entry.
   * If the repository is at max capacity, the oldest entry is removed.
   *
   * @param taskName The name of the task
   * @param taskInstance The instance ID of the task
   * @param taskData Optional task data
   * @param pickedBy Optional identifier of the scheduler instance that executed the task
   * @param timeStarted When the execution started
   * @param timeFinished When the execution finished
   * @param succeeded Whether the execution was successful
   * @param exception Optional exception if the execution failed
   * @return The created log entry
   */
  fun add(
    taskName: String,
    taskInstance: String,
    taskData: Any?,
    pickedBy: String?,
    timeStarted: Instant,
    timeFinished: Instant,
    succeeded: Boolean,
    exception: Throwable? = null
  ): ExecutionLog {
    val log = ExecutionLog(
      id = idGenerator.incrementAndGet(),
      taskName = taskName,
      taskInstance = taskInstance,
      taskData = taskData,
      pickedBy = pickedBy,
      timeStarted = timeStarted,
      timeFinished = timeFinished,
      succeeded = succeeded,
      durationMs = Duration
        .between(timeStarted, timeFinished)
        .toMillis(),
      exceptionClass = exception?.javaClass?.name,
      exceptionMessage = exception?.message,
      exceptionStackTrace = exception?.stackTraceToString()
    )

    logs.addFirst(log)

    // Trim old entries
    while (logs.size > maxSize) {
      logs.pollLast()
    }

    return log
  }

  fun getAll(): List<ExecutionLog> = logs.toList()

  fun count(): Int = logs.size

  fun clear() {
    logs.clear()
  }
}
