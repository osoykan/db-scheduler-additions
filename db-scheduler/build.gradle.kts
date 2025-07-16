plugins {
  kotlin("jvm") version libs.versions.kotlin
  `java-test-fixtures`
}

dependencies {
  api(libs.dbScheduler)
  api(libs.jackson.kotlin)
  api(libs.jackson.databind)
  api(libs.jackson.datatype.jsr310)
  api(libs.kotlinx.coroutines.core)
  api(libs.micrometer.prometheus)
  implementation(libs.slf4j.api)
  implementation(libs.arrow.core)
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)

  testFixturesImplementation(libs.kotest.property.jvm)
  testFixturesImplementation(libs.kotest.runner.junit5)
  testFixturesImplementation(libs.datafaker)
  testFixturesImplementation(libs.arrow.core)
}
