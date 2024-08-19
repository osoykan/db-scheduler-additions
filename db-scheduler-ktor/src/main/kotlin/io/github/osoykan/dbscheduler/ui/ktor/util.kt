package io.github.osoykan.dbscheduler.ui.ktor

import arrow.core.*

private val loader = object {}.javaClass.classLoader

internal fun getResourceAsText(path: String): String = loader.getResourceAsStream(path)
  .toOption().map { r -> r.bufferedReader().use { it.readText() } }
  .getOrElse { "" }



