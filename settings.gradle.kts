@file:Suppress("UnstableApiUsage")

rootProject.name = "db-scheduler-additions"

include(
  "db-scheduler",
  "db-scheduler-couchbase",
  "db-scheduler-mongo",
  "db-scheduler-ui-ktor",
  "examples",
  "examples:db-scheduler-ktor-example"
)

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
  }
}
