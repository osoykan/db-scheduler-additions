group = "com.trendyol"

plugins {
  kotlin("jvm") version libs.versions.kotlin
  alias(libs.plugins.spotless)
  alias(libs.plugins.kover)
}

subprojects {
  apply {
    plugin("kotlin")
    plugin(rootProject.libs.plugins.spotless.pluginId)
    plugin(rootProject.libs.plugins.kover.pluginId)
  }

  dependencies {
    kover(project)
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
