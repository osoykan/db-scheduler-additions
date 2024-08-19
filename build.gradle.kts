import com.vanniktech.maven.publish.SonatypeHost

group = "io.github.osoykan"
version = "0.0.1-SNAPSHOT"

plugins {
  kotlin("jvm") version libs.versions.kotlin
  alias(libs.plugins.spotless)
  alias(libs.plugins.kover)
  alias(libs.plugins.testLogger)
  alias(libs.plugins.maven.publish)
  signing
}

subprojects {
  apply {
    plugin("kotlin")
    plugin(rootProject.libs.plugins.spotless.pluginId)
    plugin(rootProject.libs.plugins.kover.pluginId)
    plugin(rootProject.libs.plugins.testLogger.pluginId)
  }

  testlogger {
    setTheme("mocha")
    showExceptions = true
    showFailedStandardStreams = true
    showFailed = true
  }

  dependencies {
    kover(project)
  }

  tasks.test {
    useJUnitPlatform()
  }

  kotlin {
    compilerOptions {
      jvmToolchain(17)
      freeCompilerArgs = listOf("-Xjsr305=strict", "-Xcontext-receivers")
      allWarningsAsErrors = true
    }
  }

  spotless {
    kotlin {
      ktlint().setEditorConfigPath(rootProject.file(".editorconfig"))
      targetExcludeIfContentContains("generated")
    }
  }
}

val publishedProjects = listOf(
  projects.dbSchedulerUiKtor.name
)


subprojects.of(projects.dbSchedulerAdditions.name, filter = { p -> publishedProjects.contains(p.name) }) {
  apply {
    plugin(rootProject.libs.plugins.maven.publish.pluginId)
  }

  mavenPublishing {
    coordinates(groupId = rootProject.group.toString(), artifactId = project.name, version = rootProject.version.toString())
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    pom {
      name.set(project.name)
      description.set(project.properties["projectDescription"].toString())
      url.set(project.properties["projectUrl"].toString())
      licenses {
        license {
          name.set(project.properties["licence"].toString())
          url.set(project.properties["licenceUrl"].toString())
        }
      }
      developers {
        developer {
          id.set("osoykan")
          name.set("Oguzhan Soykan")
          email.set("")
        }
      }
      scm {
        connection.set("scm:git@github.com:osoykan/db-scheduler-additions.git")
        developerConnection.set("scm:git:ssh://github.com:osoykan/db-scheduler-additions.git")
        url.set(project.properties["projectUrl"].toString())
      }
    }
    signAllPublications()
  }
}

