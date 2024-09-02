plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  implementation(projects.dbScheduler)
  implementation(libs.mongodb.kotlin.coroutine)
  implementation(libs.slf4j.api)
  implementation(libs.arrow.core)
}

dependencies {
  testImplementation(libs.kotest.framework.api.jvm)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
  testImplementation(libs.testcontainers.mongo)
  testImplementation(testFixtures(projects.dbScheduler))
}
