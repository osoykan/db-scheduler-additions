plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  implementation(libs.micrometer.prometheus)
  implementation(libs.mongodb.kotlin.coroutine)
  implementation(libs.mongodb.bson.kotlin)
  implementation(libs.dbScheduler)
  implementation(libs.jackson.kotlin)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.slf4j.api)
  implementation(libs.arrow.core)
}

dependencies {
  testImplementation(libs.kotest.framework.api.jvm)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.stove.testing)
  testImplementation(libs.stove.testing.mongo)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
