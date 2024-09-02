package io.github.osoykan.scheduler.couchbase

import io.github.osoykan.scheduler.TaskEntity
import java.time.Instant

internal class CouchbaseTaskEntity(
  override val taskName: String,
  override val taskInstance: String,
  override val taskData: ByteArray,
  override val executionTime: Instant?,
  override val picked: Boolean,
  override val pickedBy: String?,
  override val consecutiveFailures: Int,
  override val lastSuccess: Instant?,
  override val lastFailure: Instant?,
  override val lastHeartbeat: Instant?,
  override val version: Long,
  override val metadata: MutableMap<String, Any> = mutableMapOf(),
  override val identity: String = documentId(taskName, taskInstance)
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
    metadata,
    identity
  ) {
  fun copy(
    taskName: String = this.taskName,
    taskInstance: String = this.taskInstance,
    taskData: ByteArray = this.taskData,
    executionTime: Instant? = this.executionTime,
    picked: Boolean = this.picked,
    pickedBy: String? = this.pickedBy,
    consecutiveFailures: Int = this.consecutiveFailures,
    lastSuccess: Instant? = this.lastSuccess,
    lastFailure: Instant? = this.lastFailure,
    lastHeartbeat: Instant? = this.lastHeartbeat,
    version: Long = this.version,
    metadata: MutableMap<String, Any> = this.metadata
  ): CouchbaseTaskEntity = CouchbaseTaskEntity(
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
    metadata
  )

  fun cas(): Long = metadata["cas"].toString().toLong()

  fun cas(long: Long) {
    metadata["cas"] = long
  }
}
