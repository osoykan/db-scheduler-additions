package io.github.osoykan.dbscheduler.couchbase

import arrow.core.raise.option
import arrow.core.toOption
import com.couchbase.client.core.io.CollectionIdentifier
import com.couchbase.client.kotlin.*
import com.couchbase.client.kotlin.Collection
import io.github.osoykan.dbscheduler.common.DocumentDatabase
import org.slf4j.LoggerFactory

data class Couchbase(
  val cluster: Cluster,
  val bucketName: String,
  override val collection: String = CollectionIdentifier.DEFAULT_COLLECTION
) : DocumentDatabase<Couchbase> {
  private val logger = LoggerFactory.getLogger(Couchbase::class.java)
  private val bucket = cluster.bucket(bucketName)
  private val defaultScope = bucket.defaultScope()
  val schedulerCollection: Collection by lazy { collection.let { cluster.bucket(bucketName).collection(it) } }

  override suspend fun ensureCollectionExists() {
    option {
      val collection = collection.toOption().bind()
      val exists = bucket.collections.getScope(defaultScope.name).collections.any { it.name == collection }
      if (exists) {
        logger.debug("Collection $collection already exists")
        return@option
      }

      logger.debug("Creating collection $collection")
      cluster.bucket(bucketName).collections.createCollection(defaultScope.name, collection)
      logger.debug("Collection $collection created")
    }
  }

  override fun withCollection(collection: String): Couchbase = copy(collection = collection)
}
