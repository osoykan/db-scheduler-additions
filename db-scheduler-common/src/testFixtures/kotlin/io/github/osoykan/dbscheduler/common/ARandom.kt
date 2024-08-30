package io.github.osoykan.dbscheduler.common

import net.datafaker.Faker

object ARandom {
  private val faker = Faker()

  fun text(): String = faker.idNumber().valid()
}
