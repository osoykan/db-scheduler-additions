package io.github.osoykan.scheduler.mongo

import io.github.osoykan.scheduler.TaskEntity
import org.bson.codecs.pojo.annotations.*
import java.time.Instant

class MongoTaskEntity
  @BsonCreator
  constructor(
    @param:BsonProperty("taskName")
    override val taskName: String,
    @param:BsonProperty("taskInstance")
    override val taskInstance: String,
    @param:BsonProperty("taskData")
    override val taskData: ByteArray,
    @param:BsonProperty("executionTime")
    override val executionTime: Instant?,
    @param:BsonProperty("picked")
    override val picked: Boolean,
    @param:BsonProperty("pickedBy")
    override val pickedBy: String?,
    @param:BsonProperty("consecutiveFailures")
    override val consecutiveFailures: Int,
    @param:BsonProperty("lastSuccess")
    override val lastSuccess: Instant?,
    @param:BsonProperty("lastFailure")
    override val lastFailure: Instant?,
    @param:BsonProperty("lastHeartbeat")
    override val lastHeartbeat: Instant?,
    @param:BsonProperty("version")
    override val version: Long,
    @param:BsonProperty("metadata")
    override val metadata: MutableMap<String, Any> = mutableMapOf(),
    @param:BsonProperty("identity")
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
    ): MongoTaskEntity = MongoTaskEntity(
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
  }
