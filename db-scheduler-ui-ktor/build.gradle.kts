plugins {
  kotlin("jvm") version libs.versions.kotlin
}

dependencies {
  implementation(libs.ktor.server.core)
  implementation(libs.dbScheduler)
}

// Optional: copy frontend built assets from submodule if present
val frontendBuildDir = rootProject.layout.projectDirectory.dir("external/db-scheduler-ui-frontend/db-scheduler-ui-frontend/build").asFile
val resourcesTarget = layout.projectDirectory.dir("src/main/resources/static/db-scheduler").asFile

if (frontendBuildDir.exists()) {
  tasks.register<Copy>("copyFrontend") {
    description = "Copy built db-scheduler-ui frontend into resources"
    from(frontendBuildDir)
    into(resourcesTarget)
  }
  tasks.named("processResources").configure {
    dependsOn("copyFrontend")
  }
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
