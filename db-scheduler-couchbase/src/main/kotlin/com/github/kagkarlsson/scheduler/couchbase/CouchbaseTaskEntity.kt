package com.github.kagkarlsson.scheduler.couchbase

import io.github.osoykan.dbscheduler.common.TaskEntity
import java.time.Instant

class CouchbaseTaskEntity(
  override val taskName: String,
  override val taskInstance: String,
  override val taskData: ByteArray,
  override val executionTime: Instant?,
  override val picked: Boolean,
  override val pickedBy: String?,
  override val consecutiveFailures: Int?,
  override val lastSuccess: Instant?,
  override val lastFailure: Instant?,
  override val lastHeartbeat: Instant?,
  override val version: Long = 0,
  override val identity: String = "$taskName-$taskInstance"
) : TaskEntity(
    taskName,
    taskInstance,
    taskData,
    executionTime,
    picked,
    pickedBy,
    consecutiveFailures,
    lastSuccess,
    lastFailure,
    lastHeartbeat,
    version,
    identity
  ) {
  fun cas(): Long = metadata["cas"] as Long

  fun cas(long: Long) {
    metadata["cas"] = long
  }
}
