plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  implementation(libs.dbScheduler)
  implementation(libs.dbScheduler.ui) {
    exclude(group = "org.springframework")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-webflux")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-autoconfigure")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-thymeleaf")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-json")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter")
  }
  implementation(libs.jackson.kotlin)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.slf4j.api)
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

  implementation(libs.koin.ktor)
  implementation(libs.koin)
}

dependencies {
  testImplementation(libs.kotest.framework.api.jvm)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.stove.testing)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
