plugins {
  kotlin("jvm") version libs.versions.kotlin
  `java-test-fixtures`
}

dependencies {
  api(libs.dbScheduler)
  api(libs.kotlinx.coroutines.core)
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

  testFixturesImplementation(libs.kotest.property.jvm)
  testFixturesImplementation(libs.kotest.runner.junit5)
  testFixturesApi(libs.datafaker)
}
