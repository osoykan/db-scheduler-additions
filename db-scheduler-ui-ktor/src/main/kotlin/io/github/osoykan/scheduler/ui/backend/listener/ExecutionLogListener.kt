package io.github.osoykan.scheduler.ui.backend.listener

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import io.github.osoykan.scheduler.ui.backend.repository.LogRepository
import java.time.Instant

/**
 * A SchedulerListener that captures task execution history into a LogRepository.
 * This listener tracks when executions complete (both success and failure) and
 * stores the execution details for display in the history UI.
 */
class ExecutionLogListener(
  private val logRepository: LogRepository,
  private val captureTaskData: Boolean = true
) : AbstractSchedulerListener() {
  override fun onExecutionComplete(executionComplete: ExecutionComplete) {
    val execution = executionComplete.execution
    val taskInstance = execution.taskInstance
    val duration = executionComplete.duration
    val result = executionComplete.result

    val timeFinished = Instant.now()
    val timeStarted = timeFinished.minusMillis(duration.toMillis())

    val succeeded = result == ExecutionComplete.Result.OK
    val exception = executionComplete.cause.orElse(null)

    logRepository.add(
      taskName = taskInstance.taskName,
      taskInstance = taskInstance.id,
      taskData = if (captureTaskData) taskInstance.data else null,
      pickedBy = execution.pickedBy,
      timeStarted = timeStarted,
      timeFinished = timeFinished,
      succeeded = succeeded,
      exception = exception
    )
  }
}
