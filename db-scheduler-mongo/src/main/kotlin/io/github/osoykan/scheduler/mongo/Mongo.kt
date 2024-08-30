package io.github.osoykan.scheduler.mongo

import com.mongodb.kotlin.client.coroutine.*
import io.github.osoykan.dbscheduler.common.DocumentDatabase
import kotlinx.coroutines.flow.toList

data class Mongo(
  val client: MongoClient,
  val database: String,
  override val collection: String = "scheduler"
) : DocumentDatabase<Mongo> {
  private val databaseOps = client.getDatabase(database)
  val schedulerCollection: MongoCollection<MongoTaskEntity> by lazy { databaseOps.getCollection(collection) }

  override suspend fun ensureCollectionExists() {
    val exists = databaseOps.listCollectionNames().toList().any { it == collection }
    if (exists) return
    databaseOps.createCollection(collection)
  }

  override fun withCollection(collection: String): Mongo = copy(collection = collection)
}
