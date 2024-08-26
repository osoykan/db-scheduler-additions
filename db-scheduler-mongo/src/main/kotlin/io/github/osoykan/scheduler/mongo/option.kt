package io.github.osoykan.scheduler.mongo

import arrow.core.Option
import java.util.*

fun <T> Option<T>.asJava() = Optional.ofNullable(this.getOrNull())

fun <T> Optional<T>.asArrow(): Option<T> = Option.fromNullable(this.orElse(null))
