package io.github.osoykan.dbscheduler.ui.ktor

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import org.koin.core.KoinApplication
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import java.io.InputStream

private val loader = object {}.javaClass.classLoader

fun getResourceAsStream(path: String): InputStream = loader.getResourceAsStream(path)!!

fun getResourceAsText(path: String): String = getResourceAsStream(path).bufferedReader().use { it.readText() }

fun KoinApplication.registerJacksonSerialization() {
  modules(module { single { JacksonConfiguration.default } })
}

fun Application.configureContentNegotiation() {
  val mapper: ObjectMapper by inject()
  install(ContentNegotiation) {
    register(ContentType.Application.Json, JacksonConverter(mapper))
    register(ContentType.Application.ProblemJson, JacksonConverter(mapper))
  }
}
