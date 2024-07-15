package com.github.kagkarlsson.scheduler.couchbase

import com.github.kagkarlsson.scheduler.task.*
import java.time.Instant

class TaskEntity(
  val taskName: String,
  val taskInstance: String,
  val taskData: ByteArray,
  val executionTime: Instant?,
  val isPicked: Boolean,
  val pickedBy: String?,
  val consecutiveFailures: Int?,
  val lastSuccess: Instant?,
  val lastFailure: Instant?,
  val lastHeartbeat: Instant?,
  private val id: String = "$taskName-$taskInstance"
) : EnrichedWithMetadata() {
  fun copy(
    taskName: String = this.taskName,
    taskInstance: String = this.taskInstance,
    taskData: ByteArray = this.taskData,
    executionTime: Instant? = this.executionTime,
    isPicked: Boolean = this.isPicked,
    pickedBy: String? = this.pickedBy,
    consecutiveFailures: Int? = this.consecutiveFailures,
    lastSuccess: Instant? = this.lastSuccess,
    lastFailure: Instant? = this.lastFailure,
    lastHeartbeat: Instant? = this.lastHeartbeat,
    metadata: Map<String, Any> = this.internalMetadata
  ): TaskEntity = TaskEntity(
    taskName = taskName,
    taskInstance = taskInstance,
    taskData = taskData,
    executionTime = executionTime,
    isPicked = isPicked,
    pickedBy = pickedBy,
    consecutiveFailures = consecutiveFailures,
    lastSuccess = lastSuccess,
    lastFailure = lastFailure,
    lastHeartbeat = lastHeartbeat
  ).apply {
    metadata.forEach { (key, value) -> setMetadata(key, value) }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TaskEntity

    if (taskName != other.taskName) return false
    if (taskInstance != other.taskInstance) return false
    if (!taskData.contentEquals(other.taskData)) return false
    if (executionTime != other.executionTime) return false
    if (isPicked != other.isPicked) return false
    if (pickedBy != other.pickedBy) return false
    if (lastSuccess != other.lastSuccess) return false
    if (lastFailure != other.lastFailure) return false
    if (lastHeartbeat != other.lastHeartbeat) return false
    if (id != other.id) return false

    return true
  }

  override fun hashCode(): Int {
    var result = taskName.hashCode()
    result = 31 * result + taskInstance.hashCode()
    result = 31 * result + taskData.contentHashCode()
    result = 31 * result + executionTime.hashCode()
    result = 31 * result + isPicked.hashCode()
    result = 31 * result + pickedBy.hashCode()
    result = 31 * result + lastSuccess.hashCode()
    result = 31 * result + lastFailure.hashCode()
    result = 31 * result + lastHeartbeat.hashCode()
    result = 31 * result + id.hashCode()
    return result
  }

  companion object {
    fun documentId(taskName: String, taskInstanceId: String): String = "$taskName-$taskInstanceId"
  }
}

fun Execution.documentId(): String = TaskEntity.documentId(taskName, taskInstance.id)

fun SchedulableInstance<*>.documentId(): String = TaskEntity.documentId(taskName, id)
