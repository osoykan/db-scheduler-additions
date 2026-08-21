package io.github.osoykan.scheduler.ui.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class StaticResourcesTests :
  FunSpec({
    test("normalizeContextPath should handle empty string") {
      normalizeContextPath("") shouldContain ""
    }

    test("normalizeContextPath should handle blank string") {
      normalizeContextPath("   ") shouldContain ""
    }

    test("normalizeContextPath should add leading slash") {
      normalizeContextPath("my-app") shouldContain "/my-app"
    }

    test("normalizeContextPath should remove trailing slash") {
      normalizeContextPath("/my-app/") shouldContain "/my-app"
    }

    test("normalizeContextPath should handle already normalized path") {
      normalizeContextPath("/my-app") shouldContain "/my-app"
    }

    test("normalizeContextPath should handle path with both issues") {
      normalizeContextPath("my-app/") shouldContain "/my-app"
    }

    test("rewriteIndexHtmlWithContextPath should inject script tag") {
      val html = "<html><head></head><body></body></html>"
      val rewritten = rewriteIndexHtmlWithContextPath(html, "/my-app")
      rewritten shouldContain "<script>window.CONTEXT_PATH='/my-app';</script>"
    }

    test("rewriteIndexHtmlWithContextPath should update asset paths") {
      val html = "<script src='/db-scheduler/assets/test.js'></script>"
      val rewritten = rewriteIndexHtmlWithContextPath(html, "/my-app")
      rewritten shouldContain "/my-app/db-scheduler/assets/test.js"
    }

    test("rewriteIndexHtmlWithContextPath should update favicon path") {
      val html = "<link href='/db-scheduler/favicon.svg' rel='icon' />"
      val rewritten = rewriteIndexHtmlWithContextPath(html, "/my-app")
      rewritten shouldContain "/my-app/db-scheduler/favicon.svg"
    }
  })
