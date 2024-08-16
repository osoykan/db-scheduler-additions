package io.github.osoykan.dbscheduler.ui.ktor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object JacksonConfiguration {
  val default: ObjectMapper = JsonMapper.builder()
    .findAndAddModules()
    .build()
    .registerKotlinModule()
    .findAndRegisterModules()
    .registerModule(JavaTimeModule())
}
