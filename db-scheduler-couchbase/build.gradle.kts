plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  api(projects.dbScheduler)
  implementation(libs.couchbase.client.kotlin)
  implementation(libs.slf4j.api)
  implementation(libs.arrow.core)
}

dependencies {
  testImplementation(libs.kotest.framework.api.jvm)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
  testImplementation(libs.testcontainers.couchbase)
  testImplementation(testFixtures(projects.dbScheduler))
}
