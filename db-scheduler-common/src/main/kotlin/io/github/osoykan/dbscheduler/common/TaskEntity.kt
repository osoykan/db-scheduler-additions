package io.github.osoykan.dbscheduler.common

import com.github.kagkarlsson.scheduler.task.*
import java.time.Instant

open class TaskEntity(
  open val taskName: String,
  open val taskInstance: String,
  open val taskData: ByteArray,
  open val executionTime: Instant?,
  open val picked: Boolean,
  open val pickedBy: String?,
  open val consecutiveFailures: Int?,
  open val lastSuccess: Instant?,
  open val lastFailure: Instant?,
  open val lastHeartbeat: Instant?,
  open val version: Long = 0,
  open val identity: String = "$taskName-$taskInstance"
) {
  val metadata: MutableMap<String, Any> = mutableMapOf()

  fun <T : Any> setMetadata(
    key: String,
    value: T
  ) {
    metadata[key] = value
  }

  fun hasMetadata(key: String): Boolean = metadata.containsKey(key)

  fun removeMetadata(key: String) {
    if (hasMetadata(key)) {
      metadata.remove(key)
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun <T : TaskEntity> copy(
    taskName: String = this.taskName,
    taskInstance: String = this.taskInstance,
    taskData: ByteArray = this.taskData,
    executionTime: Instant? = this.executionTime,
    picked: Boolean = this.picked,
    pickedBy: String? = this.pickedBy,
    consecutiveFailures: Int? = this.consecutiveFailures,
    lastSuccess: Instant? = this.lastSuccess,
    lastFailure: Instant? = this.lastFailure,
    lastHeartbeat: Instant? = this.lastHeartbeat,
    version: Long = this.version,
    metadata: Map<String, Any> = this.metadata
  ): T = TaskEntity(
    taskName = taskName,
    taskInstance = taskInstance,
    taskData = taskData,
    executionTime = executionTime,
    picked = picked,
    pickedBy = pickedBy,
    consecutiveFailures = consecutiveFailures,
    lastSuccess = lastSuccess,
    lastFailure = lastFailure,
    lastHeartbeat = lastHeartbeat,
    version = version
  ).apply {
    metadata.forEach { (key, value) -> setMetadata(key, value) }
  } as T

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TaskEntity

    if (taskName != other.taskName) return false
    if (taskInstance != other.taskInstance) return false
    if (!taskData.contentEquals(other.taskData)) return false
    if (executionTime != other.executionTime) return false
    if (picked != other.picked) return false
    if (pickedBy != other.pickedBy) return false
    if (lastSuccess != other.lastSuccess) return false
    if (lastFailure != other.lastFailure) return false
    if (lastHeartbeat != other.lastHeartbeat) return false
    if (version != other.version) return false
    if (identity != other.identity) return false

    return true
  }

  override fun hashCode(): Int {
    var result = taskName.hashCode()
    result = 31 * result + taskInstance.hashCode()
    result = 31 * result + taskData.contentHashCode()
    result = 31 * result + executionTime.hashCode()
    result = 31 * result + picked.hashCode()
    result = 31 * result + pickedBy.hashCode()
    result = 31 * result + lastSuccess.hashCode()
    result = 31 * result + lastFailure.hashCode()
    result = 31 * result + lastHeartbeat.hashCode()
    result = 31 * result + version.hashCode()
    result = 31 * result + identity.hashCode()
    return result
  }

  companion object {
    fun documentId(taskName: String, taskInstanceId: String): String = "$taskName-$taskInstanceId"
  }
}

fun Execution.documentId(): String = TaskEntity.documentId(taskName, taskInstance.id)

fun SchedulableInstance<*>.documentId(): String = TaskEntity.documentId(taskName, id)
