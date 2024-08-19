plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  implementation(projects.dbSchedulerUiKtor)
  implementation(libs.dbScheduler)
  implementation(libs.jackson.kotlin)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.jackson.parameternames)
  implementation(libs.slf4j.api)
  implementation(libs.logback.classic)
  implementation(libs.janino)
  implementation(libs.arrow.core)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.statuspages)
  implementation(libs.ktor.server.callLogging)
  implementation(libs.ktor.server.callId)
  implementation(libs.ktor.server.conditionalHeaders)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.defaultHeaders)
  implementation(libs.ktor.server.cachingHeaders)
  implementation(libs.ktor.server.autoHeadResponse)
  implementation(libs.ktor.server.config.yml)
  implementation(libs.ktor.serialization.jackson.json)
  implementation(libs.kotlinlogging)
  implementation(libs.hikari)
  implementation(libs.postgresql)
  implementation(libs.koin.ktor)
  implementation(libs.koin)
  implementation(libs.testcontainers.postgresql)
}

dependencies {
  testImplementation(libs.kotest.framework.api.jvm)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.stove.testing)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
