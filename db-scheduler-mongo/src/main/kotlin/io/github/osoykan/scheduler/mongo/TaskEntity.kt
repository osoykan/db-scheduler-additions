package io.github.osoykan.scheduler.mongo

import com.github.kagkarlsson.scheduler.task.*
import org.bson.codecs.pojo.annotations.*
import java.time.Instant

class TaskEntity
  @BsonCreator
  constructor(
    @BsonProperty("taskName")
    val taskName: String,
    @BsonProperty("taskInstance")
    val taskInstance: String,
    @BsonProperty("taskData")
    val taskData: ByteArray,
    @BsonProperty("executionTime")
    val executionTime: Instant?,
    @BsonProperty("picked")
    val picked: Boolean,
    @BsonProperty("pickedBy")
    val pickedBy: String?,
    @BsonProperty("consecutiveFailures")
    val consecutiveFailures: Int?,
    @BsonProperty("lastSuccess")
    val lastSuccess: Instant?,
    @BsonProperty("lastFailure")
    val lastFailure: Instant?,
    @BsonProperty("lastHeartbeat")
    val lastHeartbeat: Instant?,
    @BsonProperty("version")
    val version: Long = 0,
    @BsonProperty("identity")
    val identity: String = "$taskName-$taskInstance"
  ) : WithMetadata() {
    fun copy(
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
      metadata: Map<String, Any> = this.internalMetadata
    ): TaskEntity = TaskEntity(
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
