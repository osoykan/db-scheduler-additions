plugins {
  kotlin("jvm") version libs.versions.kotlin
  alias(libs.plugins.node)
}

// Configure sourcesJar to exclude UI build artifacts
tasks.withType<Jar>().configureEach {
  if (name == "sourcesJar") {
    exclude("static/scheduler-ui/node_modules/**")
    exclude("static/scheduler-ui/dist/**")
    exclude("static/scheduler-ui/package-lock.json")
    exclude("static/db-scheduler/**")
  }
}

val uiSourcePath = project.layout.projectDirectory.dir("src/main/resources/static/scheduler-ui")
val uiDistPath = uiSourcePath.dir("dist")
val targetPath = project.layout.projectDirectory.dir("src/main/resources/static/db-scheduler")

node {
  version.set("24.3.0")
  download.set(true)
  npmInstallCommand.set("install")
  nodeProjectDir.set(uiSourcePath)
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("buildUI") {
  description = "Build React UI from source files copied by db-scheduler-ui-updater.sh"
  args.set(listOf("run", "build"))
  dependsOn("nodeSetup", "npmSetup", "npmInstall")
  inputs.dir(uiSourcePath.dir("src"))
  inputs.file(uiSourcePath.file("package.json"))
  inputs.file(uiSourcePath.file("vite.config.ts"))
  outputs.dir(uiDistPath)
}

tasks.register<Copy>("copyBuiltUI") {
  description = "Copy built React UI to db-scheduler static directory"
  dependsOn("buildUI")
  from(uiDistPath)
  into(targetPath)
}

// Conditionally depend on UI build for processResources
val hasUISource = project.layout.projectDirectory.dir("src/main/resources/static/scheduler-ui/src").asFile.exists()
if (hasUISource) {
  tasks.named("processResources").configure {
    dependsOn("copyBuiltUI")
  }
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(libs.ktor.server.core)
  implementation(libs.dbScheduler)
  
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
