@file:Suppress("UnstableApiUsage")

rootProject.name = "db-scheduler-additions"

include(
  "db-scheduler",
  "db-scheduler-mongo",
  "db-scheduler-ui-ktor",
  "examples",
  "examples:db-scheduler-ktor-example"
)

pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
  }
}
