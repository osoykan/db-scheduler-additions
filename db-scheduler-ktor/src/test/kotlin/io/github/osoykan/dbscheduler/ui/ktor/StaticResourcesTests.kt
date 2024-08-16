package io.github.osoykan.dbscheduler.ui.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class StaticResourcesTests : FunSpec({
  test("should get resource as stream") {
    val html = getResourceAsText("static/db-scheduler/index.html")
    html shouldContain "/db-scheduler/assets/index-ed4b70bb.js"
    html shouldContain "/db-scheduler/assets/index-c49f4573.css"
  }
})
