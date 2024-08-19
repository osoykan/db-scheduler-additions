import arrow.core.*

private val loader = object {}.javaClass.classLoader

fun getResourceAsText(path: String): String = loader.getResourceAsStream(path)
  .toOption().map { r -> r.bufferedReader().use { it.readText() } }
  .getOrElse { "" }
