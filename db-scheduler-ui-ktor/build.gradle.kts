plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  implementation(libs.ktor.server.core)
  implementation(libs.dbScheduler)
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
