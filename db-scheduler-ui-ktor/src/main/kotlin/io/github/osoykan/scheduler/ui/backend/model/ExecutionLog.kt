package io.github.osoykan.scheduler.ui.backend.model

import java.time.Instant

/**
 * Representation of an execution log entry.
 */
data class ExecutionLog(
  val id: Long = 0,
  val taskName: String,
  val taskInstance: String,
  val taskData: Any?,
  val pickedBy: String?,
  val timeStarted: Instant,
  val timeFinished: Instant,
  val succeeded: Boolean,
  val durationMs: Long,
  val exceptionClass: String?,
  val exceptionMessage: String?,
  val exceptionStackTrace: String?
)
