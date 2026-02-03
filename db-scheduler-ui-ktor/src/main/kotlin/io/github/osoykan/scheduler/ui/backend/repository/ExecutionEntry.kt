package io.github.osoykan.scheduler.ui.backend.repository

import java.time.Instant

data class ExecutionEntry(
  val id: Long,
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
