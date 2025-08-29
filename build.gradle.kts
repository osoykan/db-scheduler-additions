group = "io.github.osoykan"
version = "0.0.7"

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

  dependencies {
    kover(project)
  }

  tasks.test {
    useJUnitPlatform()
    testlogger {
      setTheme("mocha")
      showExceptions = true
      showFailedStandardStreams = true
      showFailed = true
    }
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2)
  }

  kotlin {
    compilerOptions {
      jvmToolchain(17)
      freeCompilerArgs = listOf(
        "-Xjsr305=strict",
        "-Xcontext-parameters",
        "-Xnested-type-aliases",
        "-Xwhen-guards",
        "-Xsuppress-version-warnings",
        "-Xwarning-level=IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE:disabled",
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-opt-in=kotlin.contracts.ExperimentalContracts"
      )
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
  projects.dbScheduler.name,
  projects.dbSchedulerMongo.name,
  projects.dbSchedulerUiKtor.name,
)

subprojects.of(projects.dbSchedulerAdditions.name, filter = { p -> publishedProjects.contains(p.name) }) {
  apply {
    plugin(rootProject.libs.plugins.maven.publish.pluginId)
  }

  mavenPublishing {
    coordinates(groupId = rootProject.group.toString(), artifactId = project.name, version = rootProject.version.toString())
    publishToMavenCentral()
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

