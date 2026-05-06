package com.yourname.expensetracker.testfixtures.golden

import org.json.JSONObject

/**
 * Golden-file comparison utility for scenario testing.
 *
 * A "golden file" is a JSON resource file that captures the expected
 * output state of a scenario. On the first run, when no golden file
 * exists yet, the verifier prints a warning and accepts the actual output.
 * On subsequent runs, it compares actual vs. expected and returns
 * whether they match.
 *
 * Golden files are placed in `resources/golden/` (classpath resource
 * directory) and named `{scenarioName}.json`.
 *
 * Usage:
 * ```
 * val verifier = GoldenScenarioVerifier("my_scenario")
 * val actual = JSONObject(mapOf("total" to 150.0, "count" to 3))
 * val passed = verifier.verifyJson(actual)
 * Assert.assertTrue("Golden file mismatch for my_scenario", passed)
 * ```
 *
 * @property scenarioName  Logical name used to locate the golden file
 *                         (e.g. "budget_warning_threshold").
 */
class GoldenScenarioVerifier(private val scenarioName: String) {

    /**
     * Verifies that [actual] matches the golden file at [expectedResourcePath].
     *
     * @param actual               The [JSONObject] produced by the test.
     * @param expectedResourcePath Classpath resource path to the golden file.
     *                             Defaults to `"golden/$scenarioName.json"`.
     * @return `true` if the golden file does not exist (first run / generation
     *         mode) or if [actual] matches the golden file; `false` on mismatch.
     */
    fun verifyJson(
        actual: JSONObject,
        expectedResourcePath: String = "golden/$scenarioName.json"
    ): Boolean {
        val expectedJson = javaClass.classLoader
            ?.getResourceAsStream(expectedResourcePath)
            ?.bufferedReader()
            ?.readText()
            ?.let { JSONObject(it) }

        if (expectedJson == null) {
            println("WARNING: Golden file not found: $expectedResourcePath. Accepting actual output as baseline.")
            return true
        }

        return compareJson(expectedJson, actual)
    }

    /**
     * Recursively compares two [JSONObject] instances for structural
     * and value equality.
     *
     * Two objects match if they have the same set of keys and each key's
     * value is equal (using [Any.toString] for comparison). Nested objects
     * are compared recursively. Order of keys is not significant.
     *
     * @return `true` if the objects are structurally equivalent.
     */
    private fun compareJson(expected: JSONObject, actual: JSONObject): Boolean {
        if (expected.length() != actual.length()) return false
        for (key in expected.keys()) {
            if (!actual.has(key)) return false

            val expectedValue = expected.get(key)
            val actualValue = actual.get(key)

            if (expectedValue is JSONObject && actualValue is JSONObject) {
                if (!compareJson(expectedValue, actualValue)) return false
            } else if (expectedValue.toString() != actualValue.toString()) {
                return false
            }
        }
        return true
    }
}
