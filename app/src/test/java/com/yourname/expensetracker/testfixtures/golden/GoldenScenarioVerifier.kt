package com.yourname.expensetracker.testfixtures.golden

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Strict golden-file comparison utility for scenario testing.
 *
 * Behavior:
 * - **Default (CI):** missing golden file = test FAILURE.
 * - **Update mode:** set system property `updateGoldens=true` or Gradle property
 *   `-PupdateGoldens=true` to write actual output as the new golden file.
 *
 * Features:
 * - JSONObject and JSONArray comparison
 * - Numeric tolerance (default 0.01 for money values)
 * - Ignored fields (unstable timestamps, IDs)
 * - Pretty diff output on mismatch
 * - Sorted array comparison where order is not contractual
 *
 * Golden files live in `app/src/test/resources/golden/{scenarioName}.json`.
 */
class GoldenScenarioVerifier(
    private val scenarioName: String,
    private val numericTolerance: Double = 0.01,
    private val ignoredFields: Set<String> = emptySet(),
    private val sortArraysByField: String? = null
) {

    companion object {
        private val UPDATE_MODE: Boolean by lazy {
            System.getProperty("updateGoldens")?.toBoolean() == true
        }

        private fun goldenResourceDir(): File? {
            val projectDir = File(System.getProperty("user.dir") ?: return null)
            // Try app/src/test/resources/golden first, then src/test/resources/golden
            val candidates = listOf(
                File(projectDir, "app/src/test/resources/golden"),
                File(projectDir, "src/test/resources/golden")
            )
            val existing = candidates.firstOrNull { it.exists() }
            if (existing != null) return existing
            // Default: create in the first candidate that can be created
            val target = candidates.first()
            return if (target.mkdirs() || target.exists()) target else null
        }
    }

    data class VerificationResult(
        val passed: Boolean,
        val diffs: List<String> = emptyList(),
        val goldenPath: String = ""
    ) {
        fun assertPassed() {
            if (!passed) {
                throw AssertionError(
                    "Golden file mismatch for '$goldenPath':\n${diffs.joinToString("\n")}"
                )
            }
        }
    }

    /**
     * Verifies [actual] against the golden file. Missing file = FAIL.
     */
    fun verify(
        actual: JSONObject,
        subPath: String = "$scenarioName.json"
    ): VerificationResult {
        val resourcePath = "golden/$subPath"

        if (UPDATE_MODE) {
            writeGoldenFile(actual.toString(2), subPath)
            return VerificationResult(passed = true, goldenPath = resourcePath)
        }

        val expectedText = javaClass.classLoader
            ?.getResourceAsStream(resourcePath)
            ?.bufferedReader()
            ?.readText()

        if (expectedText == null) {
            return VerificationResult(
                passed = false,
                diffs = listOf(
                    "Golden file NOT FOUND: $resourcePath",
                    "Run with -DupdateGoldens=true to generate it.",
                    "Actual output:\n${actual.toString(2)}"
                ),
                goldenPath = resourcePath
            )
        }

        val expected = JSONObject(expectedText)
        val diffs = mutableListOf<String>()
        compareJson(expected, actual, "$", diffs)

        return VerificationResult(passed = diffs.isEmpty(), diffs = diffs, goldenPath = resourcePath)
    }

    /**
     * Verifies a JSONArray against the golden file.
     */
    fun verifyArray(
        actual: JSONArray,
        subPath: String = "$scenarioName.json"
    ): VerificationResult {
        val resourcePath = "golden/$subPath"

        if (UPDATE_MODE) {
            writeGoldenFile(actual.toString(2), subPath)
            return VerificationResult(passed = true, goldenPath = resourcePath)
        }

        val expectedText = javaClass.classLoader
            ?.getResourceAsStream(resourcePath)
            ?.bufferedReader()
            ?.readText()

        if (expectedText == null) {
            return VerificationResult(
                passed = false,
                diffs = listOf(
                    "Golden file NOT FOUND: $resourcePath",
                    "Run with -DupdateGoldens=true to generate it.",
                    "Actual output:\n${actual.toString(2)}"
                ),
                goldenPath = resourcePath
            )
        }

        val expected = JSONArray(expectedText)
        val diffs = mutableListOf<String>()
        compareArray(expected, actual, "$", diffs)

        return VerificationResult(passed = diffs.isEmpty(), diffs = diffs, goldenPath = resourcePath)
    }

    // ── Comparison engine ──

    private fun compareJson(expected: JSONObject, actual: JSONObject, path: String, diffs: MutableList<String>) {
        val expectedKeys = expected.keys().asSequence().toSet() - ignoredFields
        val actualKeys = actual.keys().asSequence().toSet() - ignoredFields

        (expectedKeys - actualKeys).forEach { diffs.add("$path: missing key '$it'") }
        (actualKeys - expectedKeys).forEach { diffs.add("$path: unexpected key '$it'") }

        for (key in expectedKeys.intersect(actualKeys)) {
            compareValues(expected.get(key), actual.get(key), "$path.$key", diffs)
        }
    }

    private fun compareValues(expected: Any?, actual: Any?, path: String, diffs: MutableList<String>) {
        when {
            expected is JSONObject && actual is JSONObject ->
                compareJson(expected, actual, path, diffs)
            expected is JSONArray && actual is JSONArray ->
                compareArray(expected, actual, path, diffs)
            expected is Number && actual is Number ->
                compareNumeric(expected, actual, path, diffs)
            expected == JSONObject.NULL && actual == JSONObject.NULL -> {}
            else -> {
                val e = expected?.toString() ?: "null"
                val a = actual?.toString() ?: "null"
                if (e != a) diffs.add("$path: expected=$e, actual=$a")
            }
        }
    }

    private fun compareNumeric(expected: Number, actual: Number, path: String, diffs: MutableList<String>) {
        val diff = Math.abs(expected.toDouble() - actual.toDouble())
        if (diff > numericTolerance) {
            diffs.add("$path: expected=${expected.toDouble()}, actual=${actual.toDouble()} (diff=$diff, tolerance=$numericTolerance)")
        }
    }

    private fun compareArray(expected: JSONArray, actual: JSONArray, path: String, diffs: MutableList<String>) {
        if (expected.length() != actual.length()) {
            diffs.add("$path: array length expected=${expected.length()}, actual=${actual.length()}")
            return
        }
        val eSorted = maybeSortArray(expected)
        val aSorted = maybeSortArray(actual)
        for (i in 0 until eSorted.length()) {
            compareValues(eSorted.get(i), aSorted.get(i), "$path[$i]", diffs)
        }
    }

    private fun maybeSortArray(array: JSONArray): JSONArray {
        if (sortArraysByField == null) return array
        val items = (0 until array.length()).map { array.get(it) }
        val sorted = items.sortedBy {
            if (it is JSONObject && it.has(sortArraysByField)) it.getString(sortArraysByField)
            else it.toString()
        }
        return JSONArray(sorted)
    }

    // ── Update mode ──

    private fun writeGoldenFile(content: String, subPath: String) {
        val dir = goldenResourceDir() ?: run {
            println("WARNING: Could not resolve golden resource directory for writing.")
            return
        }
        val file = File(dir, subPath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        println("GOLDEN UPDATED: ${file.absolutePath}")
    }
}
