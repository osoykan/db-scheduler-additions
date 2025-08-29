plugins {
  kotlin("jvm") version libs.versions.kotlin
  alias(libs.plugins.node)
}

val uiPath = project.layout.projectDirectory.dir("src/main/resources/static/scheduler-ui")
val distPath = uiPath.dir("dist")

node {
  version.set("24.3.0")
  download.set(true)
  npmInstallCommand.set("install")
  nodeProjectDir.set(uiPath)
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("npmBuild") {
  description = "Run npm build command"
  args.set(listOf("run", "build"))
  dependsOn("nodeSetup", "npmSetup", "npmInstall")
  inputs.dir(uiPath)
  outputs.dir(distPath)
}

repositories {
  mavenCentral()
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
