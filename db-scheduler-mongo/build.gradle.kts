plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  api(projects.dbScheduler)
  implementation(libs.mongodb.kotlin.coroutine)
  implementation(libs.slf4j.api)
  implementation(libs.arrow.core)
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
  testImplementation(libs.testcontainers.mongo)
  testImplementation(testFixtures(projects.dbScheduler))
}

tasks.test {
  // Exclude abstract test classes from being run directly
  exclude("**/SchedulerUseCases.class")
}
