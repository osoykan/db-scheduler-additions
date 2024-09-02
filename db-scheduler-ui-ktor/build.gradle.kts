plugins {
  kotlin("jvm") version libs.versions.kotlin
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
  testImplementation(libs.kotest.framework.api.jvm)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.logback.classic)
  testImplementation(libs.janino)
}
