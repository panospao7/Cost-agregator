package com.yourname.expensetracker.data.ai.provider.internal

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CloudJsonParserTest {

    @Test
    fun `extractFirstJsonObject returns first complete object from mixed text`() {
        val text = "prefix text {\"a\":1,\"nested\":{\"b\":2}} suffix text"

        val extracted = CloudJsonParser.extractFirstJsonObject(text)

        assertEquals("{\"a\":1,\"nested\":{\"b\":2}}", extracted)
    }

    @Test
    fun `extractFirstJsonObject handles braces inside quoted strings`() {
        val text = "intro {\"note\":\"literal { brace } text\",\"ok\":true} tail"

        val extracted = CloudJsonParser.extractFirstJsonObject(text)

        assertEquals("{\"note\":\"literal { brace } text\",\"ok\":true}", extracted)
    }

    @Test
    fun `extractFirstJsonObject handles escaped quotes and backslashes`() {
        val text = "x {\"text\":\"escaped quote \\\" and path C:\\\\Temp\\\\x\",\"n\":1} y"

        val extracted = CloudJsonParser.extractFirstJsonObject(text)

        assertEquals("{\"text\":\"escaped quote \\\" and path C:\\\\Temp\\\\x\",\"n\":1}", extracted)
    }

    @Test
    fun `extractFirstJsonObject returns first object when multiple are present`() {
        val text = "before {\"first\":1} middle {\"second\":2}"

        val extracted = CloudJsonParser.extractFirstJsonObject(text)

        assertEquals("{\"first\":1}", extracted)
    }

    @Test
    fun `extractFirstJsonObject returns null for missing or incomplete json`() {
        assertNull(CloudJsonParser.extractFirstJsonObject("no json here"))
        assertNull(CloudJsonParser.extractFirstJsonObject("prefix {\"a\":1"))
    }

    @Test
    fun `extractFirstJsonObject prefers fenced json when available`() {
        val text = """
            preliminary {not-json}
            ```json
            {"ok":true,"value":7}
            ```
            trailing text
        """.trimIndent()

        val extracted = CloudJsonParser.extractFirstJsonObject(text)

        assertEquals("{\"ok\":true,\"value\":7}", extracted)
    }

    @Test
    fun `extractFencedJsonObject extracts json from fenced block`() {
        val text = """
            response:
            ```json
            {"answer":"yes","meta":{"k":1}}
            ```
        """.trimIndent()

        val extracted = CloudJsonParser.extractFencedJsonObject(text)

        assertEquals("{\"answer\":\"yes\",\"meta\":{\"k\":1}}", extracted)
    }

    @Test
    fun `extractFencedJsonObject returns null for missing or blank fenced block`() {
        assertNull(CloudJsonParser.extractFencedJsonObject("plain text"))

        val blankFence = """
            ```json
            
            ```
        """.trimIndent()
        assertNull(CloudJsonParser.extractFencedJsonObject(blankFence))
    }

    @Test
    fun `optFiniteDoubleStrictOrNull parses numbers and nullability correctly`() {
        val json = JSONObject("""{"a":1.25,"b":3,"c":null}""")

        with(CloudJsonParser) {
            assertEquals(1.25, json.optFiniteDoubleStrictOrNull("a")!!, 0.0)
            assertEquals(3.0, json.optFiniteDoubleStrictOrNull("b")!!, 0.0)
            assertNull(json.optFiniteDoubleStrictOrNull("c"))
            assertNull(json.optFiniteDoubleStrictOrNull("missing"))
        }
    }

    @Test
    fun `optFiniteDoubleStrictOrNull throws for non numeric values`() {
        val json = JSONObject("""{"value":"12.5"}""")

        try {
            with(CloudJsonParser) {
                json.optFiniteDoubleStrictOrNull("value")
            }
            fail("Expected JSONException for non-numeric value")
        } catch (expected: JSONException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `optStrictLongStrictOrNull parses integer and nullability correctly`() {
        val json = JSONObject("""{"a":42,"b":null}""")

        with(CloudJsonParser) {
            assertEquals(42L, json.optStrictLongStrictOrNull("a"))
            assertNull(json.optStrictLongStrictOrNull("b"))
            assertNull(json.optStrictLongStrictOrNull("missing"))
        }
    }

    @Test
    fun `optStrictLongStrictOrNull throws for decimal and non numeric values`() {
        val decimal = JSONObject("""{"value":12.34}""")
        val text = JSONObject("""{"value":"12"}""")

        try {
            with(CloudJsonParser) {
                decimal.optStrictLongStrictOrNull("value")
            }
            fail("Expected JSONException for decimal value")
        } catch (_: JSONException) {
            // expected
        }

        try {
            with(CloudJsonParser) {
                text.optStrictLongStrictOrNull("value")
            }
            fail("Expected JSONException for non-numeric value")
        } catch (_: JSONException) {
            // expected
        }
    }
}
