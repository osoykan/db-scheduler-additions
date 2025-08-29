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
  implementation(libs.dbScheduler.ui) {
    exclude(group = "org.springframework", module = "spring-webflux")
    exclude(group = "org.springframework", module = "spring-webmvc")
    exclude(group = "org.springframework", module = "spring-tx")
    exclude(group = "org.springframework", module = "spring-jcl")
    exclude(group = "org.springframework", module = "spring-expressions")
    exclude(group = "org.springframework", module = "spring-context")
    exclude(group = "org.springframework", module = "spring-beans")
    exclude(group = "org.springframework", module = "spring-aop")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-webflux")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-autoconfigure")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-thymeleaf")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-json")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter")
  }
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
