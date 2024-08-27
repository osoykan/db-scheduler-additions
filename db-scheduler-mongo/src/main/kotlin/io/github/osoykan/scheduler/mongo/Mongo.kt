package io.github.osoykan.scheduler.mongo

import com.mongodb.kotlin.client.coroutine.*
import kotlinx.coroutines.flow.toList

data class Mongo(
  val client: MongoClient,
  val database: String,
  val collection: String = "scheduler"
) {
  private val databaseOps = client.getDatabase(database)
  val schedulerCollection: MongoCollection<TaskEntity> by lazy { databaseOps.getCollection(collection) }

  suspend fun ensureCollectionExists() {
    val exists = databaseOps.listCollectionNames().toList().any { it == collection }
    if (exists) {
      return
    }
    databaseOps.createCollection(collection)
  }
}
