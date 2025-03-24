package io.github.osoykan.scheduler

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
  open val metadata: MutableMap<String, Any> = mutableMapOf(),
  open val identity: String = "$taskName-$taskInstance"
) {
  fun <T : Any> setMetadata(
    key: String,
    value: T
  ) {
    metadata[key] = value
  }

  private fun hasMetadata(key: String): Boolean = metadata.containsKey(key)

  fun removeMetadata(key: String) {
    if (hasMetadata(key)) {
      metadata.remove(key)
    }
  }

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

fun ScheduledTaskInstance.documentId(): String = TaskEntity.documentId(taskName, id)
