import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object JacksonConfiguration {
  val default: ObjectMapper = JsonMapper
    .builder()
    .apply {
      configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }.build()
    .registerKotlinModule()
    .registerModule(JavaTimeModule())
    .findAndRegisterModules()
}
