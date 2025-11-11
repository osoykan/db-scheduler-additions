package io.github.osoykan.scheduler.ui.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JsonUtilTest :
  FunSpec({

    test("should convert simple map to JSON") {
      val map = mapOf("name" to "John", "age" to 30)
      val json = map.toJson()
      json shouldBe """{"name":"John","age":30}"""
    }

    test("should convert map with null values to JSON") {
      val map = mapOf("name" to "John", "age" to null)
      val json = map.toJson()
      json shouldBe """{"name":"John","age":null}"""
    }

    test("should convert map with boolean values to JSON") {
      val map = mapOf("active" to true, "verified" to false)
      val json = map.toJson()
      json shouldBe """{"active":true,"verified":false}"""
    }

    test("should convert map with numeric values to JSON") {
      val map = mapOf("int" to 42, "double" to 3.14, "long" to 9999999999L)
      val json = map.toJson()
      json shouldBe """{"int":42,"double":3.14,"long":9999999999}"""
    }

    test("should escape special characters in JSON strings") {
      val map = mapOf(
        "quote" to "He said \"Hello\"",
        "backslash" to "C:\\path\\to\\file",
        "newline" to "Line1\nLine2",
        "tab" to "Col1\tCol2"
      )
      val json = map.toJson()
      json shouldBe """{"quote":"He said \"Hello\"","backslash":"C:\\path\\to\\file","newline":"Line1\nLine2","tab":"Col1\tCol2"}"""
    }

    test("should convert map with nested collections to JSON") {
      val map = mapOf(
        "numbers" to listOf(1, 2, 3, 4, 5),
        "names" to listOf("Alice", "Bob", "Charlie")
      )
      val json = map.toJson()
      json shouldBe """{"numbers":[1,2,3,4,5],"names":["Alice","Bob","Charlie"]}"""
    }

    test("should convert map with nested maps to JSON") {
      val map = mapOf(
        "user" to mapOf("name" to "John", "age" to 30),
        "settings" to mapOf("theme" to "dark", "notifications" to true)
      )
      val json = map.toJson()
      json shouldBe """{"user":{"name":"John","age":30},"settings":{"theme":"dark","notifications":true}}"""
    }

    test("should convert map with arrays to JSON") {
      val map = mapOf(
        "array" to arrayOf(1, 2, 3),
        "mixed" to arrayOf<Any?>("a", 1, true, null)
      )
      val json = map.toJson()
      json shouldBe """{"array":[1,2,3],"mixed":["a",1,true,null]}"""
    }

    test("should convert empty map to JSON") {
      val map = emptyMap<String, Any>()
      val json = map.toJson()
      json shouldBe "{}"
    }

    test("should handle complex nested structures") {
      val map = mapOf(
        "id" to 1,
        "name" to "Test User",
        "tags" to listOf("kotlin", "programming", "json"),
        "metadata" to mapOf(
          "created" to "2025-01-01",
          "settings" to mapOf(
            "preferences" to listOf("dark-mode", "notifications")
          )
        ),
        "active" to true
      )
      val json = map.toJson()
      json shouldBe """{"id":1,"name":"Test User","tags":["kotlin","programming","json"],"metadata":{"created":"2025-01-01","settings":{"preferences":["dark-mode","notifications"]}},"active":true}"""
    }

    test("should handle empty collections") {
      val map: Map<String, Any> = mapOf(
        "emptyList" to emptyList<String>(),
        "emptyArray" to emptyArray<Int>(),
        "data" to "value"
      )
      val json = map.toJson()
      json shouldBe """{"emptyList":[],"emptyArray":[],"data":"value"}"""
    }

    test("should handle unicode characters in keys and values") {
      val map = mapOf(
        "emoji" to "Hello 👋 World 🌍",
        "chinese" to "你好",
        "arabic" to "مرحبا"
      )
      val json = map.toJson()
      json shouldBe """{"emoji":"Hello 👋 World 🌍","chinese":"你好","arabic":"مرحبا"}"""
    }

    test("should handle control characters in strings") {
      val map = mapOf(
        "backspace" to "Test\bBackspace",
        "formfeed" to "Test\u000CFormFeed",
        "carriageReturn" to "Test\rCarriageReturn"
      )
      val json = map.toJson()
      json shouldBe """{"backspace":"Test\bBackspace","formfeed":"Test\fFormFeed","carriageReturn":"Test\rCarriageReturn"}"""
    }

    test("should convert map with custom object types to JSON") {
      data class Person(
        val name: String,
        val age: Int
      )
      val map = mapOf("person" to Person("John", 30))
      val json = map.toJson()
      json shouldBe """{"person":{"name":"John","age":30}}"""
    }

    test("should serialize nested custom objects") {
      data class Address(
        val street: String,
        val city: String
      )

      data class Person(
        val name: String,
        val age: Int,
        val address: Address
      )
      val map = mapOf(
        "user" to Person("John", 30, Address("123 Main St", "New York"))
      )
      val json = map.toJson()
      json shouldBe """{"user":{"name":"John","age":30,"address":{"street":"123 Main St","city":"New York"}}}"""
    }

    test("should serialize lists of custom objects") {
      data class Person(
        val name: String,
        val age: Int
      )
      val map = mapOf(
        "people" to listOf(
          Person("Alice", 25),
          Person("Bob", 30),
          Person("Charlie", 35)
        )
      )
      val json = map.toJson()
      json shouldBe """{"people":[{"name":"Alice","age":25},{"name":"Bob","age":30},{"name":"Charlie","age":35}]}"""
    }

    test("should serialize mixed complex and primitive types") {
      data class Config(
        val enabled: Boolean,
        val timeout: Int
      )
      val map = mapOf(
        "id" to 1,
        "name" to "Service",
        "config" to Config(enabled = true, timeout = 5000),
        "tags" to listOf("api", "core")
      )
      val json = map.toJson()
      json shouldBe """{"id":1,"name":"Service","config":{"enabled":true,"timeout":5000},"tags":["api","core"]}"""
    }

    test("should handle deeply nested complex structures") {
      data class Metrics(
        val count: Int,
        val rate: Double
      )

      data class Settings(
        val enabled: Boolean,
        val metrics: Metrics
      )

      data class Task(
        val name: String,
        val settings: Settings,
        val tags: List<String>
      )
      val complexMap = mapOf(
        "tasks" to listOf(
          Task(
            name = "task-1",
            settings = Settings(
              enabled = true,
              metrics = Metrics(count = 100, rate = 0.95)
            ),
            tags = listOf("high-priority", "critical")
          ),
          Task(
            name = "task-2",
            settings = Settings(
              enabled = false,
              metrics = Metrics(count = 50, rate = 0.85)
            ),
            tags = listOf("low-priority")
          )
        ),
        "metadata" to mapOf(
          "version" to "1.0",
          "timestamp" to "2025-11-11T00:00:00Z"
        )
      )
      val json = complexMap.toJson()
      json shouldBe
        """{"tasks":[{"name":"task-1","settings":{"enabled":true,"metrics":{"count":100,"rate":0.95}},"tags":["high-priority","critical"]},{"name":"task-2","settings":{"enabled":false,"metrics":{"count":50,"rate":0.85}},"tags":["low-priority"]}],"metadata":{"version":"1.0","timestamp":"2025-11-11T00:00:00Z"}}"""
    }

    // Tests for Any?.toJson() extension function
    test("Any?.toJson() should convert null to JSON") {
      val value: String? = null
      value.toJson() shouldBe "null"
    }

    test("Any?.toJson() should convert string to JSON") {
      "Hello World".toJson() shouldBe "\"Hello World\""
    }

    test("Any?.toJson() should convert number to JSON") {
      42.toJson() shouldBe "42"
      3.14.toJson() shouldBe "3.14"
    }

    test("Any?.toJson() should convert boolean to JSON") {
      true.toJson() shouldBe "true"
      false.toJson() shouldBe "false"
    }

    test("Any?.toJson() should convert list to JSON") {
      listOf(1, 2, 3).toJson() shouldBe "[1,2,3]"
      listOf("a", "b", "c").toJson() shouldBe """["a","b","c"]"""
    }

    test("Any?.toJson() should convert custom object to JSON") {
      data class Person(
        val name: String,
        val age: Int
      )
      Person("Alice", 25).toJson() shouldBe """{"name":"Alice","age":25}"""
    }

    test("Any?.toJson() should convert complex nested object to JSON") {
      data class Address(
        val city: String,
        val zipCode: String
      )

      data class Person(
        val name: String,
        val age: Int,
        val address: Address
      )
      Person("Bob", 30, Address("New York", "10001")).toJson() shouldBe
        """{"name":"Bob","age":30,"address":{"city":"New York","zipCode":"10001"}}"""
    }

    test("Any?.toJson() should convert list of objects to JSON") {
      data class Item(
        val id: Int,
        val name: String
      )
      listOf(
        Item(1, "Apple"),
        Item(2, "Banana")
      ).toJson() shouldBe """[{"id":1,"name":"Apple"},{"id":2,"name":"Banana"}]"""
    }
  })
