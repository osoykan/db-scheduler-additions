package io.github.osoykan.scheduler.mongo

import io.github.osoykan.scheduler.TaskEntity
import org.bson.codecs.pojo.annotations.*
import java.time.Instant

class MongoTaskEntity
  @BsonCreator
  constructor(
    @BsonProperty("taskName")
    override val taskName: String,
    @BsonProperty("taskInstance")
    override val taskInstance: String,
    @BsonProperty("taskData")
    override val taskData: ByteArray,
    @BsonProperty("executionTime")
    override val executionTime: Instant?,
    @BsonProperty("picked")
    override val picked: Boolean,
    @BsonProperty("pickedBy")
    override val pickedBy: String?,
    @BsonProperty("consecutiveFailures")
    override val consecutiveFailures: Int,
    @BsonProperty("lastSuccess")
    override val lastSuccess: Instant?,
    @BsonProperty("lastFailure")
    override val lastFailure: Instant?,
    @BsonProperty("lastHeartbeat")
    override val lastHeartbeat: Instant?,
    @BsonProperty("version")
    override val version: Long = 0,
    @BsonProperty("metadata")
    override val metadata: MutableMap<String, Any> = mutableMapOf(),
    @BsonProperty("identity")
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
