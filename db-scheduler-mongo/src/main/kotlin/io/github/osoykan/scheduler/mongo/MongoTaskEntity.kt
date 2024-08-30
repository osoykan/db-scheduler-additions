package io.github.osoykan.scheduler.mongo

import io.github.osoykan.dbscheduler.common.TaskEntity
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
    override val consecutiveFailures: Int?,
    @BsonProperty("lastSuccess")
    override val lastSuccess: Instant?,
    @BsonProperty("lastFailure")
    override val lastFailure: Instant?,
    @BsonProperty("lastHeartbeat")
    override val lastHeartbeat: Instant?,
    @BsonProperty("version")
    override val version: Long = 0,
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
      identity
    )
