package io.github.osoykan.scheduler.mongo

import com.mongodb.MongoClientSettings
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.pojo.PojoCodecProvider
import kotlin.reflect.KClass

class PojoRegistry {
  private var builder = PojoCodecProvider.builder().automatic(true)

  inline fun <reified T : Any> register(): PojoRegistry = register(T::class)

  fun <T : Any> register(clazz: KClass<T>): PojoRegistry = builder.register(clazz.java).let { this }

  fun build(): CodecRegistry = CodecRegistries.fromRegistries(
    MongoClientSettings.getDefaultCodecRegistry(),
    CodecRegistries.fromProviders(builder.build())
  )
}
