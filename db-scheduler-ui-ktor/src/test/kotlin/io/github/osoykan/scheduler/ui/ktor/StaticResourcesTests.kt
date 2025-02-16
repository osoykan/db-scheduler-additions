package io.github.osoykan.scheduler.ui.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class StaticResourcesTests :
  FunSpec({
    test("should get resource as stream") {
      val html = getResourceAsText("static/db-scheduler/index.html")
      html shouldContain "/db-scheduler/assets/index-"
    }
  })
