package com.yourname.expensetracker.guard

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Fixture tests for the DB guard policy YAML configs.
 *
 * Validates that [db_structural_exceptions.yml] and [db_ownership_policy.yml]
 * contain the exact expected entries with correct field values. These tests
 * act as a contract: if someone edits the YAML, the test fails.
 *
 * ## Coverage
 * - **Structural exceptions:** Exact class, method_pattern, operation, and path
 *   matching for the 62-entry structural policy (migrations, rescue,
 *   backup/restore, diagnostics, privacy export, restore verification).
 * - **Structural manifest:** The checked-in
 *   `config/guards/db_structural_exceptions_expected_methods.yml` is loaded and
 *   validated directly — counts block (`structural_entries: 62` ONLY; ownership
 *   cardinality is not manifest metadata and an `ownership_entries` counts key
 *   fails closed), expected
 *   58 + fixtures 4, exact union equality with the structural YAML tuple set,
 *   expected/fixtures disjointness, a single global tuple-identity set across
 *   BOTH sections (a cross-section duplicate fails closed), and no
 *   duplicate/wildcard/raw/write tuples.
 * - **Ownership policy:** Exact class, method, DAO, and DAO-operation matching for
 *   the full 99-entry policy. Every approved writer enumerates its exact DAO
 *   operation (never the generic `write` value, which the loader rejects).
 * - **Negative tests:** Unrelated class/method/DAO combinations assert they are
 *   NOT present in the policies; wildcard `method = "*"` entries are rejected.
 * - **Parser fail-closed:** unknown keys, missing required fields (including
 *   `path` and `operation`), missing/ambiguous method vs method_pattern, and
 *   missing/empty daos fail with entry/path context; manifest tuples reject
 *   blank fields, non-canonical paths, wildcard/unbounded method patterns, and
 *   operations outside the exact whitelist; manifest counts must be known,
 *   non-duplicated, integer, non-negative, and all present — and the legacy
 *   `ownership_entries` counts key is rejected as unknown-count-key metadata.
 * - **Source evidence:** class-scoped, ambiguity-safe method extraction;
 *   barrier-before-mutation ordering is verified per class/method.
 */
class DbGuardPolicyFixtureTest {

    // ── Path resolution ───────────────────────────────────────────────

    private val projectRoot: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        // When run from app/, the project root is the parent.
        if (File(cwd, "config/guards/db_structural_exceptions.yml").exists()) cwd
        else File(cwd, "..") // run from app/
    }

    private val structuralExceptionsFile: File by lazy {
        resolve("config/guards/db_structural_exceptions.yml")
    }
    private val ownershipPolicyFile: File by lazy {
        resolve("config/guards/db_ownership_policy.yml")
    }
    private val structuralManifestFile: File by lazy {
        resolve("config/guards/db_structural_exceptions_expected_methods.yml")
    }

    // ── Source files referenced by the ownership policy ────────────────
    private val exchangeRateAdapterSource: File by lazy {
        resolve("app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt")
    }
    private val promptStateRepositorySource: File by lazy {
        resolve("app/src/main/java/com/yourname/expensetracker/data/repository/PromptStateRepository.kt")
    }
    private val workerRunLoggerSource: File by lazy {
        resolve("app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt")
    }
    private val workerExecutionGuardSource: File by lazy {
        resolve("app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt")
    }

    private fun resolve(relative: String): File {
        val f = File(projectRoot, relative)
        require(f.exists()) { "Missing required config file: ${f.absolutePath}" }
        return f
    }

    // ── YAML parsing helpers ──────────────────────────────────────────
    //
    // Simple line-based parser since these YAML files are structurally
    // consistent (flat list of objects with no nesting or multi-line values
    // beyond simple inline lists).

    data class ParsedEntry(
        val path: String,
        val className: String,
        val method: String?,
        val methodPattern: String?,
        val operation: String,
        val reason: String,
        val owner: String,
        val linkedIssue: String,
        val daos: List<String>,
        val barrierRequired: Boolean?,
        val barrierVia: String?
    )

    private val knownKeys = listOf(
        "class:", "method:", "method_pattern:", "operation:", "reason:",
        "owner:", "linked_issue:", "daos:", "barrier_required:", "barrier_via:"
    )

    // The ONLY keys the structural manifest parser accepts in each section.
    // Anything else — inside `baseline:`, `counts:`, or a tuple entry — fails
    // closed instead of being silently ignored.
    private val manifestBaselineKeys = listOf("commit:", "description:", "source_note:")

    // GR-04 decoupling: the manifest governs structural exceptions ONLY.  The
    // legacy `ownership_entries` counts key is NOT accepted — it fails closed
    // as an unknown counts key instead of being silently ignored.
    private val manifestCountKeys = listOf("structural_entries:")
    private val manifestTupleKeys = listOf(
        "class:", "method_pattern:", "operation:", "reason:", "owner:", "linked_issue:"
    )

    private val requiredKeys = listOf("path", "class", "operation", "reason", "owner", "linked_issue")

    private fun parseEntries(file: File): List<ParsedEntry> {
        return parseEntriesContent(file.readLines(), file.name)
    }

    /**
     * Line-based parser for the flat entry YAML files. Fail-closed:
     * - every line inside an entry block must be a known key;
     * - every entry must define all [requiredKeys] plus exactly one of
     *   `method` / `method_pattern`;
     * - any violation throws with the entry/path and file context.
     */
    private fun parseEntriesContent(lines: List<String>, fileName: String): List<ParsedEntry> {
        val entries = mutableListOf<ParsedEntry>()
        var current: MutableMap<String, String>? = null
        var currentPath: String? = null
        var currentStartLine = 0

        for ((lineIndex, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Blank lines and comments are safe to skip anywhere.
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Start of a new entry block (line begins with "- path:")
            if (trimmed.startsWith("- path:")) {
                current?.let { finishEntry(it, entries, fileName, currentPath, currentStartLine) }
                current = mutableMapOf()
                currentPath = extractValue(trimmed, "path")
                current!!["path"] = currentPath
                currentStartLine = lineIndex + 1
                continue
            }

            if (current == null) {
                // Outside an entry: only top-level markers such as "entries:" are allowed.
                if (trimmed !in listOf("entries:", "entry:")) {
                    error(
                        "Unexpected line '${trimmed.take(60)}' outside an entry block in " +
                            "$fileName (line ${lineIndex + 1}); entries must begin with '- path:'"
                    )
                }
                continue
            }

            // Inside an entry block: every line must be a known key, else fail closed.
            val matchedKey = knownKeys.firstOrNull { trimmed.startsWith(it) }
            if (matchedKey != null) {
                current[matchedKey.removeSuffix(":")] = extractValue(trimmed, matchedKey.removeSuffix(":"))
                continue
            }

            error(
                "Unknown key '${trimmed.substringBefore(':')}' in entry '${currentPath ?: "<no path>"}' " +
                    "of $fileName (line ${lineIndex + 1})"
            )
        }

        current?.let { finishEntry(it, entries, fileName, currentPath, currentStartLine) }
        return entries
    }

    private fun finishEntry(
        raw: Map<String, String>,
        entries: MutableList<ParsedEntry>,
        fileName: String,
        entryPath: String?,
        startLine: Int
    ) {
        val context = "entry '${entryPath ?: "<no path>"}' in $fileName (starting line $startLine)"

        for (key in requiredKeys) {
            require(!raw[key].isNullOrBlank()) { "Missing required field '$key' for $context" }
        }
        val hasMethod = raw.containsKey("method")
        val hasMethodPattern = raw.containsKey("method_pattern")
        require(hasMethod != hasMethodPattern) {
            "Entry must define exactly one of 'method' or 'method_pattern' for $context"
        }

        val path = raw["path"]!!
        val className = raw["class"]!!
        val operation = raw["operation"]!!
        val reason = raw["reason"]!!
        val owner = raw["owner"]!!
        val linkedIssue = raw["linked_issue"]!!
        val method = raw["method"]
        val methodPattern = raw["method_pattern"]

        // Ownership entries (method-based) must carry a strict boolean
        // barrier_required and a non-empty daos list.  Missing or malformed
        // values fail closed with the entry/path context — never silently
        // accepted (a null boolean or an empty DAO list would weaken the
        // contract check).  Structural exception entries have neither field.
        val barrierRequired: Boolean?
        val barrierVia = raw["barrier_via"]
        val daos: List<String>
        if (hasMethod) {
            val barrierRaw = raw["barrier_required"]
            require(barrierRaw == "true" || barrierRaw == "false") {
                "barrier_required must be strictly 'true' or 'false' for $context"
            }
            barrierRequired = barrierRaw == "true"

            val daosRaw = raw["daos"]
            require(!daosRaw.isNullOrBlank()) {
                "Ownership entry $context must define a 'daos' list"
            }
            val parsedDaos = daosRaw
                .removeSurrounding("[", "]")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            require(parsedDaos.isNotEmpty()) {
                "Ownership entry $context must have a non-empty 'daos' list"
            }
            daos = parsedDaos
        } else {
            barrierRequired = null
            daos = emptyList()
        }

        entries.add(
            ParsedEntry(
                path = path,
                className = className,
                method = method,
                methodPattern = methodPattern,
                operation = operation,
                reason = reason,
                owner = owner,
                linkedIssue = linkedIssue,
                daos = daos,
                barrierRequired = barrierRequired,
                barrierVia = barrierVia
            )
        )
    }

    private fun extractValue(line: String, key: String): String {
        val afterColon = line.substringAfter("$key:").trim()
        // Remove inline comment if present (but respect values containing "#" inside quotes)
        val quoteIndex = afterColon.indexOf('"')
        if (quoteIndex >= 0) {
            val closingQuote = afterColon.indexOf('"', quoteIndex + 1)
            if (closingQuote >= 0) {
                return afterColon.substring(0, closingQuote + 1).trim('"')
            }
        }
        val commentIdx = afterColon.indexOf(" #")
        val value = if (commentIdx >= 0) afterColon.substring(0, commentIdx) else afterColon
        return value.trim().trim('"')
    }

    /** Writes [content] to a throwaway temp manifest file for parser tests. */
    private fun writeTempManifest(content: String): File {
        val f = File.createTempFile("db_guard_manifest_test_", ".yml")
        f.writeText(content, Charsets.UTF_8)
        f.deleteOnExit()
        return f
    }

    // ── Structural manifest helpers ────────────────────────────────────
    //
    // `config/guards/db_structural_exceptions_expected_methods.yml` is a
    // mandatory integrity/classification manifest consumed by
    // `scripts/test_verify_db_access_boundaries.py`. It pins the exact
    // `expected`/`fixtures` tuple classification of the structural exceptions
    // file plus the canonical entry-count contract. The loader below reads the
    // manifest itself (counts block plus expected/fixtures tuple sections) so
    // the tests validate the manifest, not hardcoded comments.

    data class ManifestTuple(
        val path: String,
        val className: String,
        val methodPattern: String,
        val operation: String
    )

    data class StructuralManifest(
        val structuralEntries: Int,
        val expected: List<ManifestTuple>,
        val fixtures: List<ManifestTuple>
    )

    /**
     * Loads the structural manifest from its checked-in YAML.
     *
     * The manifest has a `baseline:` block (documentation only — no tuple
     * semantics, but its keys are still validated), a `counts:` block, and two
     * flat tuple sections (`expected:` / `fixtures:`) whose entries use
     * `method_pattern` + exact operation.
     *
     * Fail-closed parsing:
     * - every line in `counts:` must be the single accepted key
     *   `structural_entries:` (the legacy `ownership_entries:` key is an
     *   unknown counts key and fails closed) and each counts key may appear at
     *   most once;
     * - every key inside a tuple entry must be a known manifest key and may
     *   appear at most once per entry (duplicates fail closed instead of
     *   overwriting the previous value);
     * - every tuple's `operation` must be one of the exact allowed operations
     *   (`execSQL`, `openDatabase`, `getDatabasePath`, `deleteRecursively`,
     *   `writableDatabase`) — `raw_` categories, generic `write`, and arbitrary
     *   names are rejected;
     * - every tuple identity `(path, class, method_pattern, operation)` must be
     *   unique inside its `expected:`/`fixtures:` section (duplicates fail
     *   closed);
     * - every line in `baseline:` must be a known baseline key
     *   (`commit:` / `description:` / `source_note:`) or block-scalar prose;
     * - block-scalar continuation lines (indented prose after `key: >`) are
     *   skipped because they are legitimate YAML content — but a line that
     *   *looks like a YAML key* inside a continuation must still be a known
     *   key;
     * - any structurally unexpected line, unknown key, or malformed entry
     *   (e.g. a `- class:` line without a `- path:` marker) fails closed with
     *   the file/line context.
     */
    private fun parseStructuralManifest(file: File): StructuralManifest {
        val lines = file.readLines()
        var section: String? = null
        var current: MutableMap<String, String>? = null
        val expected = mutableListOf<ManifestTuple>()
        val fixtures = mutableListOf<ManifestTuple>()
        // Single global tuple-identity set across BOTH sections: a tuple may
        // appear at most once in the entire manifest, so a duplicate that spans
        // `expected` and `fixtures` is still a duplicate and fails closed.
        val allTuples = mutableSetOf<ManifestTuple>()
        val counts = mutableMapOf<String, String>()
        var blockScalarContinuation = false

        fun flushEntry() {
            val map = current ?: return
            val path = map["path"]
                ?: error("Manifest entry missing 'path' in section '$section'")
            val className = map["class"]
                ?: error("Manifest entry missing 'class' in section '$section' ($path)")
            val methodPattern = map["method_pattern"]
                ?: error("Manifest entry missing 'method_pattern' in section '$section' ($path)")
            val operation = map["operation"]
                ?: error("Manifest entry missing 'operation' in section '$section' ($path)")

            require(path.isNotBlank()) {
                "Manifest tuple 'path' must not be blank in section '$section'"
            }
            require(className.isNotBlank()) {
                "Manifest tuple 'class' must not be blank in section '$section' (path: $path)"
            }
            require(methodPattern.isNotBlank()) {
                "Manifest tuple 'method_pattern' must not be blank in section '$section' (path: $path)"
            }
            require(isCanonicalSourcePath(path)) {
                "Manifest tuple 'path' must be canonical 'app/src/main/java/.../*.kt', " +
                    "got '$path' (section '$section')"
            }
            require(isValidMethodPattern(methodPattern)) {
                "Manifest tuple 'method_pattern' must be an exact identifier or the bounded " +
                    "MIGRATION_\\d+_\\d+ form, got '$methodPattern' (path: $path, section '$section')"
            }
            // A blank operation is rejected here as an unsupported operation
            // (an empty value is never a whitelist member), so the failure
            // stays inside the exact-operation contract.
            if (operation !in exactOperationNames) {
                error(
                    "Unsupported operation '$operation' in manifest tuple " +
                        "($path, $className.$methodPattern, section '$section'); " +
                        "allowed operations: ${exactOperationNames.sorted()}"
                )
            }
            val tuple = ManifestTuple(path, className, methodPattern, operation)
            if (!allTuples.add(tuple)) {
                error(
                    "Duplicate manifest tuple across manifest sections: " +
                        "($path, $className.$methodPattern, $operation)"
                )
            }
            when (section) {
                "expected" -> expected.add(tuple)
                "fixtures" -> fixtures.add(tuple)
                else -> error("Manifest entry found outside expected/fixtures (path: $path)")
            }
            current = null
        }

        fun knownKey(trimmed: String): String? = when (section) {
            "baseline" -> manifestBaselineKeys.firstOrNull { trimmed.startsWith(it) }?.removeSuffix(":")
            "counts" -> manifestCountKeys.firstOrNull { trimmed.startsWith(it) }?.removeSuffix(":")
            "expected", "fixtures" -> manifestTupleKeys.firstOrNull { trimmed.startsWith(it) }?.removeSuffix(":")
            else -> null
        }

        for ((lineIndex, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val context = "(${file.name}, line ${lineIndex + 1})"
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Section headers and entry starts always end any block-scalar
            // continuation.
            if (trimmed == "baseline:" || trimmed == "counts:" ||
                trimmed == "expected:" || trimmed == "fixtures:" ||
                trimmed.startsWith("- path:")
            ) {
                blockScalarContinuation = false
            }

            if (blockScalarContinuation) {
                // Inside a `key: >` block scalar.  A line shaped like a YAML
                // key (`name: ...`) must be a KNOWN key — it ends the block
                // scalar and is processed below.  Any other line is legitimate
                // continuation prose and is skipped.
                val colonIdx = trimmed.indexOf(':')
                val looksLikeKey = colonIdx > 0 &&
                    exactIdentifier.matches(trimmed.substring(0, colonIdx).trim())
                if (looksLikeKey) {
                    if (knownKey(trimmed) == null) {
                        error("Unknown key '${trimmed.substringBefore(':').trim()}' in manifest $context")
                    }
                    blockScalarContinuation = false
                } else {
                    continue
                }
            }

            when {
                trimmed == "baseline:" -> { flushEntry(); section = "baseline"; blockScalarContinuation = false }
                trimmed == "counts:" -> { flushEntry(); section = "counts"; blockScalarContinuation = false }
                trimmed == "expected:" -> { flushEntry(); section = "expected"; blockScalarContinuation = false }
                trimmed == "fixtures:" -> { flushEntry(); section = "fixtures"; blockScalarContinuation = false }
                trimmed.startsWith("- path:") -> {
                    flushEntry()
                    current = mutableMapOf("path" to extractValue(trimmed, "path"))
                    blockScalarContinuation = false
                }
                section == "counts" -> {
                    val key = knownKey(trimmed)
                        ?: error("Unknown key '${trimmed.substringBefore(':').trim()}' in manifest $context")
                    if (counts.containsKey(key)) {
                        error("Duplicate counts key '$key' in manifest $context")
                    }
                    counts[key] = trimmed.substringAfter(':').trim()
                }
                current != null && (section == "expected" || section == "fixtures") -> {
                    val key = knownKey(trimmed)
                        ?: error("Unknown key '${trimmed.substringBefore(':').trim()}' in manifest $context")
                    if (current!!.containsKey(key)) {
                        error(
                            "Duplicate key '$key' in manifest tuple entry " +
                                "(section '$section', path '${current!!["path"]}') $context"
                        )
                    }
                    current!![key] = extractValue(trimmed, key)
                    // A `key: >` block scalar marks the following indented
                    // lines as continuation prose.
                    if (extractValue(trimmed, key) == ">") {
                        blockScalarContinuation = true
                    }
                }
                section == "baseline" -> {
                    val key = knownKey(trimmed)
                        ?: error("Unknown key '${trimmed.substringBefore(':').trim()}' in manifest $context")
                    // Baseline keys carry no tuple semantics; only recognized
                    // keys are accepted so a typo fails closed instead of being
                    // silently ignored.
                    if (extractValue(trimmed, key) == ">") {
                        blockScalarContinuation = true
                    }
                }
                else -> error("Unexpected line '${trimmed.take(60)}' in manifest $context")
            }
        }
        flushEntry()

        // GR-04 decoupling: the manifest pins the structural count ONLY.
        // Ownership cardinality is an observational migration metric tracked
        // in the ownership policy itself — never manifest metadata.  A legacy
        // `ownership_entries` line never reaches this point: it is rejected
        // above as an unknown counts key.
        val structuralRaw = counts["structural_entries"]
            ?: error("Manifest missing 'structural_entries' count")
        val structuralEntries = structuralRaw.toIntOrNull()
            ?: error("Manifest 'structural_entries' count must be an integer, got '$structuralRaw'")
        require(structuralEntries >= 0) {
            "Manifest 'structural_entries' count must not be negative, got $structuralEntries"
        }

        return StructuralManifest(
            structuralEntries = structuralEntries,
            expected = expected,
            fixtures = fixtures
        )
    }

    /** Converts a parsed structural exception entry into a manifest tuple. */
    private fun ParsedEntry.toManifestTuple(): ManifestTuple {
        require(methodPattern != null) {
            "Structural tuple requires a method_pattern, got ${className}/${path}"
        }
        return ManifestTuple(path, className, methodPattern!!, operation)
    }

    private val exactOperationNames = setOf(
        "execSQL", "openDatabase", "getDatabasePath", "deleteRecursively", "writableDatabase"
    )
    private val exactIdentifier = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * True when [pattern] is the single bounded migration form.
     *
     * The checked-in YAML stores it as a double-quoted scalar
     * (`"MIGRATION_\\d+_\\d+"`), so the line-based parser sees the escaped
     * form with a double backslash. Both the raw escaped form and the decoded
     * single-backslash form are accepted — never `.*`, `*`, or alternation.
     */
    private fun isBoundedMigrationForm(pattern: String): Boolean {
        return pattern == "MIGRATION_\\\\d+_\\\\d+" || pattern == "MIGRATION_\\d+_\\d+"
    }

    /**
     * Canonical repository-relative source path: exactly
     * `app/src/main/java/.../Foo.kt` (any `.kt` filename under
     * `app/src/main/java`).  Rejects bare filenames, non-`.kt`
     * files, backslash separators, and `..` traversal segments.
     */
    private val canonicalSourcePathPattern = Regex("^app/src/main/java/[A-Za-z0-9_./-]+\\.kt$")

    private fun isCanonicalSourcePath(path: String): Boolean {
        return canonicalSourcePathPattern.matches(path) && !path.contains("..")
    }

    /**
     * True when [pattern] is a valid exact method pattern: a Kotlin
     * identifier or the single bounded migration form `MIGRATION_\d+_\d+`.
     * Wildcards (`*`, `.*`, `.+`) and unbounded/alternation forms are
     * rejected.
     */
    private fun isValidMethodPattern(pattern: String): Boolean {
        return exactIdentifier.matches(pattern) || isBoundedMigrationForm(pattern)
    }

    /**
     * Validates one structural tuple: exact method_pattern (Kotlin identifier or
     * the single bounded migration form `MIGRATION_\d+_\d+`), exact supported
     * operation (no `raw_` category, no generic `write`, no DAO-style write
     * operation).
     */
    private fun assertValidStructuralTuple(tuple: ManifestTuple, where: String) {
        val pattern = tuple.methodPattern
        assertTrue(
            "Method pattern must be an exact identifier or MIGRATION_\\d+_\\d+, got '$pattern' in $where",
            exactIdentifier.matches(pattern) || isBoundedMigrationForm(pattern)
        )
        assertNotEquals("method_pattern must not be wildcard '*' in $where", "*", pattern)
        assertNotEquals("method_pattern must not be wildcard '.*' in $where", ".*", pattern)
        assertFalse(
            "Operation must not be a raw_ category, got '${tuple.operation}' in $where",
            tuple.operation.startsWith("raw_")
        )
        assertNotEquals("Operation must not be generic 'write' in $where", "write", tuple.operation)
        assertTrue(
            "Operation must be one of the exact supported names $exactOperationNames, " +
                "got '${tuple.operation}' in $where",
            tuple.operation in exactOperationNames
        )
    }

    // ── Source-evidence helpers ────────────────────────────────────────

    /**
     * Extracts the declaration + body text of [methodName] declared inside the
     * class [className] in [source].
     *
     * Resolution is class-scoped and ambiguity-safe: the class declaration must
     * appear exactly once and the method declaration must appear exactly once
     * within that class body. Zero or multiple matches fail explicitly — the
     * extraction never falls back to "last declaration wins" (which would
     * silently pick the wrong overload when an interface member shares the name
     * with the implementing class, e.g. WorkerRunLogger vs WorkerRunLoggerImpl).
     */
    private fun extractMethodBody(source: File, className: String, methodName: String): String {
        val lines = source.readLines()

        val classMatches = lines.mapIndexedNotNull { index, line ->
            index.takeIf { line.contains("class $className") }
        }
        require(classMatches.size == 1) {
            "Expected exactly one declaration of class '$className' in ${source.name}, found ${classMatches.size}"
        }

        val classStart = classMatches.first()
        val classBodyEnd = findScopeEnd(lines, classStart)

        val methodMatches = (classStart until classBodyEnd).mapNotNull { index ->
            index.takeIf { lines[index].contains("fun $methodName(") }
        }
        require(methodMatches.size == 1) {
            "Expected exactly one declaration of 'fun $methodName(' inside class '$className' " +
                "in ${source.name}, found ${methodMatches.size}"
        }

        val start = methodMatches.first()
        val body = StringBuilder()
        var depth = 0
        var started = false
        for (i in start until lines.size) {
            val line = lines[i]
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth > 0) started = true
            body.append(line).append('\n')
            if (started && depth == 0) break
        }
        return body.toString()
    }

    /**
     * Returns the index of the first line that closes the scope opened at
     * [start]. A scope only ends after its opening `{` has been seen (class
     * declarations can span multiple constructor-parameter lines before the
     * brace), so lines before the opener never count as an end.
     */
    private fun findScopeEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        var opened = false
        var i = start
        while (i < lines.size) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth > 0) opened = true
            if (opened && depth == 0) return i
            i++
        }
        return lines.size
    }

    private fun assertBarrierBeforeMutation(
        source: File,
        className: String,
        methodName: String,
        barrierCall: String,
        mutationCall: String
    ) {
        val body = extractMethodBody(source, className, methodName)
        val barrierIdx = body.indexOf(barrierCall)
        val mutationIdx = body.indexOf(mutationCall)
        assertTrue(
            "Method '$className.$methodName' must call '$barrierCall' before '$mutationCall' in ${source.name}",
            barrierIdx >= 0 && mutationIdx >= 0 && barrierIdx < mutationIdx
        )
    }

    private fun assertEntryMetadata(
        entry: ParsedEntry,
        operation: String,
        expectedDaos: Set<String>,
        barrierRequired: Boolean?,
        barrierVia: String?,
        reasonHint: String? = null
    ) {
        assertEquals("${entry.className}.${entry.method} DAOs", expectedDaos, entry.daos.toSet())
        assertEquals("${entry.className}.${entry.method} operation", operation, entry.operation)
        assertEquals(
            "${entry.className}.${entry.method} barrier_required",
            barrierRequired, entry.barrierRequired
        )
        assertEquals(
            "${entry.className}.${entry.method} barrier_via",
            barrierVia, entry.barrierVia
        )
        assertEquals("${entry.className}.${entry.method} owner", "@panospao7", entry.owner)
        assertEquals("${entry.className}.${entry.method} linked_issue", "MIT-003", entry.linkedIssue)
        if (reasonHint != null) {
            assertTrue(
                "${entry.className}.${entry.method} reason must mention '$reasonHint'",
                entry.reason.contains(reasonHint, ignoreCase = true)
            )
        }
    }

    // ── Assertion helpers ─────────────────────────────────────────────

    private fun findEntry(
        entries: List<ParsedEntry>,
        className: String,
        methodName: String? = null,
        methodPattern: String? = null
    ): ParsedEntry? {
        return entries.firstOrNull { entry ->
            entry.className == className &&
                (methodName == null || entry.method == methodName) &&
                (methodPattern == null || entry.methodPattern == methodPattern)
        }
    }

    private fun ParsedEntry.assertField(fieldName: String, expected: String) {
        val actual = when (fieldName) {
            "operation" -> operation
            "reason" -> reason
            "owner" -> owner
            "linked_issue" -> linkedIssue
            else -> error("Unknown field: $fieldName")
        }
        assertEquals("Entry $className.$method field $fieldName", expected, actual)
    }

    private fun ParsedEntry.assertDaos(expected: Set<String>) {
        assertEquals("Entry $className.$method DAOs", expected, daos.toSet())
    }

    // ══════════════════════════════════════════════════════════════════
    // Batch 2 — Structural exceptions
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `structural exceptions — BackupVerifier verify matches exact openDatabase tuple`() {
        val entries = parseEntries(structuralExceptionsFile)
        val entry = findEntry(entries, "BackupVerifier", methodPattern = "verify")
        assertNotNull("BackupVerifier verify entry not found in structural exceptions", entry)

        assertEquals("operation", "openDatabase", entry!!.operation)
        assertEquals(
            "path",
            "app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt",
            entry.path
        )
        assertTrue("Reason must mention restore verification",
            entry.reason.contains("restore", ignoreCase = true) || entry.reason.contains("verify", ignoreCase = true))
        assertTrue("Reason must mention read-only",
            entry.reason.contains("read-only", ignoreCase = true) || entry.reason.contains("open", ignoreCase = true))
        assertEquals("owner", "@panospao7", entry.owner)
        assertEquals("linked_issue", "MIT-003", entry.linkedIssue)
    }

    @Test
    fun `structural exceptions — BackupVerifier verifyQuick matches exact openDatabase tuple`() {
        val entries = parseEntries(structuralExceptionsFile)
        val entry = findEntry(entries, "BackupVerifier", methodPattern = "verifyQuick")
        assertNotNull("BackupVerifier verifyQuick entry not found in structural exceptions", entry)

        assertEquals("operation", "openDatabase", entry!!.operation)
        assertEquals(
            "path",
            "app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt",
            entry.path
        )
        assertTrue("Reason must mention restore verification",
            entry.reason.contains("restore", ignoreCase = true) || entry.reason.contains("verify", ignoreCase = true))
        assertTrue("Reason must mention fast or pre-swap",
            entry.reason.contains("fast", ignoreCase = true) || entry.reason.contains("pre-swap", ignoreCase = true))
        assertEquals("owner", "@panospao7", entry.owner)
        assertEquals("linked_issue", "MIT-003", entry.linkedIssue)
    }

    @Test
    fun `structural exceptions — SqliteSnapshotCreator tryVacuumInto matches exactly openDatabase and execSQL`() {
        val entries = parseEntries(structuralExceptionsFile)
        val snapshotEntries = entries.filter { it.className == "SqliteSnapshotCreator" }
        assertEquals("SqliteSnapshotCreator must have exactly 2 tuples", 2, snapshotEntries.size)
        assertEquals(
            "SqliteSnapshotCreator tryVacuumInto operations",
            setOf("openDatabase", "execSQL"),
            snapshotEntries.map { it.operation }.toSet()
        )

        for (entry in snapshotEntries) {
            assertEquals("method_pattern", "tryVacuumInto", entry.methodPattern)
            assertEquals(
                "path",
                "app/src/main/java/com/yourname/expensetracker/data/backup/SqliteSnapshotCreator.kt",
                entry.path
            )
            assertTrue("Reason must mention snapshot or VACUUM",
                entry.reason.contains("snapshot", ignoreCase = true) ||
                    entry.reason.contains("VACUUM"))
            assertEquals("owner", "@panospao7", entry.owner)
            assertEquals("linked_issue", "MIT-003", entry.linkedIssue)
        }
    }

    @Test
    fun `structural exceptions — BackupVerifier entries use exact method_pattern not wildcard`() {
        val entries = parseEntries(structuralExceptionsFile)
        val backupVerifierEntries = entries.filter {
            it.className == "BackupVerifier" && it.operation == "openDatabase"
        }
        assertEquals("Expected exactly 2 BackupVerifier entries", 2, backupVerifierEntries.size)

        for (entry in backupVerifierEntries) {
            val pattern = entry.methodPattern
            assertNotNull("method_pattern must be set for BackupVerifier", pattern)
            assertNotEquals("BackupVerifier method_pattern must not be wildcard", ".*", pattern)
            assertNotEquals("BackupVerifier method_pattern must not be '*'", "*", pattern)
        }
    }

    @Test
    fun `structural exceptions — SqliteSnapshotCreator entries use exact method_pattern not wildcard`() {
        val entries = parseEntries(structuralExceptionsFile)
        val snapshotEntries = entries.filter { it.className == "SqliteSnapshotCreator" }
        assertEquals("Expected exactly 2 SqliteSnapshotCreator entries", 2, snapshotEntries.size)

        for (entry in snapshotEntries) {
            val pattern = entry.methodPattern
            assertNotNull("method_pattern must be set for SqliteSnapshotCreator", pattern)
            assertNotEquals("SqliteSnapshotCreator method_pattern must not be wildcard", ".*", pattern)
            assertNotEquals("SqliteSnapshotCreator method_pattern must not be '*'", "*", pattern)
        }
    }

    @Test
    fun `structural exceptions — unrelated method not present`() {
        val entries = parseEntries(structuralExceptionsFile)
        // An unrelated method that should NOT be in structural exceptions
        val entry = findEntry(entries, "CategoryRepository")
        assertNull("CategoryRepository should NOT be in structural exceptions", entry)
    }

    // ══════════════════════════════════════════════════════════════════
    // Batch 2b — Structural manifest
    // (config/guards/db_structural_exceptions_expected_methods.yml)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `manifest — ownership policy has exactly 99 entries`() {
        val entries = parseEntries(ownershipPolicyFile)
        assertEquals("Ownership policy must have exactly 99 entries", 99, entries.size)
    }

    @Test
    fun `manifest — structural exceptions has exactly 62 entries`() {
        val entries = parseEntries(structuralExceptionsFile)
        assertEquals("Structural exceptions must have exactly 62 entries", 62, entries.size)
    }

    @Test
    fun `manifest — counts block pins structural 62 only`() {
        // GR-04 decoupling: the manifest governs structural exceptions ONLY.
        // Its counts block carries structural_entries and nothing else; the
        // ownership policy's own 99-entry size is an independent property of
        // the policy file, never manifest metadata.
        val manifest = parseStructuralManifest(structuralManifestFile)
        assertEquals("Manifest structural_entries count", 62, manifest.structuralEntries)
        assertEquals(
            "Manifest structural count must match the checked-in structural YAML",
            parseEntries(structuralExceptionsFile).size, manifest.structuralEntries
        )
    }

    @Test
    fun `manifest parser fails closed on legacy ownership_entries count key`() {
        // Old-shape metadata — a counts block that still pins ownership
        // cardinality — is unknown-count-key configuration and must fail
        // closed even when every structural value is correct.  If ownership
        // is ever re-coupled into the manifest contract, this assertion fails.
        val manifest = writeTempManifest(
            """
            counts:
              ownership_entries: 99
              structural_entries: 62
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must reject the legacy ownership_entries count key",
            thrown
        )
        assertTrue(
            "Failure must identify 'ownership_entries' as an unknown counts key",
            thrown!!.message!!.contains("Unknown key") &&
                thrown.message!!.contains("ownership_entries")
        )
    }

    @Test
    fun `manifest — expected has exactly 58 tuples and fixtures exactly 4`() {
        val manifest = parseStructuralManifest(structuralManifestFile)
        assertEquals("Manifest expected tuples", 58, manifest.expected.size)
        assertEquals("Manifest fixture tuples", 4, manifest.fixtures.size)
        assertEquals(
            "expected + fixtures must total the structural 62",
            62, manifest.expected.size + manifest.fixtures.size
        )
    }

    @Test
    fun `manifest — expected plus fixtures union equals structural YAML tuple set exactly`() {
        val manifest = parseStructuralManifest(structuralManifestFile)
        val structuralTuples = parseEntries(structuralExceptionsFile)
            .map { it.toManifestTuple() }
            .toSet()
        val union = (manifest.expected + manifest.fixtures).toSet()
        assertEquals("Expected+fixtures union must have exactly 62 distinct tuples", 62, union.size)
        assertEquals(
            "Expected+fixtures union must EXACTLY equal the structural YAML tuple set",
            structuralTuples, union
        )
    }

    @Test
    fun `manifest — expected and fixtures tuple sets are disjoint`() {
        val manifest = parseStructuralManifest(structuralManifestFile)
        val expectedSet = manifest.expected.toSet()
        val fixtureSet = manifest.fixtures.toSet()
        assertEquals("expected must contain no duplicate tuples", expectedSet.size, manifest.expected.size)
        assertEquals("fixtures must contain no duplicate tuples", fixtureSet.size, manifest.fixtures.size)
        val overlap = expectedSet.intersect(fixtureSet)
        assertTrue("expected and fixtures must be disjoint; overlap: $overlap", overlap.isEmpty())
    }

    @Test
    fun `manifest — expected has no duplicate, wildcard, raw, or write tuples`() {
        val manifest = parseStructuralManifest(structuralManifestFile)
        assertEquals("expected must have 58 distinct tuples", 58, manifest.expected.toSet().size)
        for (tuple in manifest.expected) {
            assertValidStructuralTuple(
                tuple,
                "manifest expected (${tuple.path} ${tuple.className}.${tuple.methodPattern})"
            )
        }
    }

    @Test
    fun `manifest — fixtures have no duplicate, wildcard, raw, or write tuples`() {
        val manifest = parseStructuralManifest(structuralManifestFile)
        assertEquals("fixtures must have 4 distinct tuples", 4, manifest.fixtures.toSet().size)
        for (tuple in manifest.fixtures) {
            assertValidStructuralTuple(
                tuple,
                "manifest fixtures (${tuple.path} ${tuple.className}.${tuple.methodPattern})"
            )
        }
    }

    @Test
    fun `structural exceptions — no duplicate tuples, wildcard, raw, or write operations`() {
        val entries = parseEntries(structuralExceptionsFile)
        val tuples = entries.map { it.toManifestTuple() }
        assertEquals("Structural YAML must have 62 distinct tuples", 62, tuples.toSet().size)
        for (tuple in tuples) {
            assertValidStructuralTuple(
                tuple,
                "structural YAML (${tuple.path} ${tuple.className}.${tuple.methodPattern})"
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Batch 3 — Ownership Policy
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `ownership — GroupTransactionCoordinator entries are exactly enumerated with exact DAOs and operations`() {
        val entries = parseEntries(ownershipPolicyFile)
        // Every approved (method, DAO, operation) triple — no wildcard entry.
        // DAO accessor identities: ExpenseGroupDao -> expenseGroupDao,
        // GroupMemberDao -> groupMemberDao, GroupExpenseDao -> groupExpenseDao,
        // ExpenseDao -> expenseDao.
        data class Spec(val daos: Set<String>, val operation: String, val hint: String)

        val expected = mapOf(
            "createGroupWithMembers" to listOf(
                Spec(setOf("expenseGroupDao"), "insert", "group"),
                Spec(setOf("groupMemberDao"), "insertAll", "member")
            ),
            "addMemberToGroup" to listOf(
                Spec(setOf("groupMemberDao"), "insert", "member")
            ),
            "addExpenseToGroup" to listOf(
                Spec(setOf("groupExpenseDao"), "insert", "group")
            ),
            "addExpenseWithLink" to listOf(
                Spec(setOf("groupExpenseDao"), "insert", "group")
            ),
            "deleteGroup" to listOf(
                Spec(setOf("expenseGroupDao"), "archiveGroup", "soft")
            ),
            "archiveGroup" to listOf(
                Spec(setOf("expenseGroupDao"), "archiveGroup", "archive")
            ),
            "createGroupWithMembersAtomic" to listOf(
                Spec(setOf("expenseGroupDao"), "insert", "group"),
                Spec(setOf("groupMemberDao"), "insertAll", "member")
            ),
            "createSystemExpenseAndLinkToGroup" to listOf(
                Spec(setOf("groupExpenseDao"), "insert", "system")
            ),
            "addExpenseToGroupAtomic" to listOf(
                Spec(setOf("groupExpenseDao"), "insert", "group")
            ),
            "deleteGroupAtomic" to listOf(
                Spec(setOf("groupExpenseDao"), "deleteAllForGroup", "group"),
                Spec(setOf("groupMemberDao"), "deleteAllForGroup", "member"),
                Spec(setOf("expenseGroupDao"), "delete", "parent"),
                Spec(setOf("expenseDao"), "clearSharedExpenseFlags", "shared")
            )
        )

        val groupEntries = entries.filter { it.className == "GroupTransactionCoordinator" }
        val expectedTotal = expected.values.sumOf { it.size }
        assertEquals(
            "GroupTransactionCoordinator must have exactly $expectedTotal entries",
            expectedTotal, groupEntries.size
        )

        for ((methodName, specs) in expected) {
            for (spec in specs) {
                val entry = groupEntries.firstOrNull {
                    it.method == methodName && it.daos.toSet() == spec.daos
                }
                assertNotNull(
                    "GroupTransactionCoordinator.$methodName entry for DAOs ${spec.daos} not found in ownership policy",
                    entry
                )
                assertEntryMetadata(entry!!, spec.operation, spec.daos, true, null, spec.hint)
            }
        }

        // No wildcard / no drift: every GroupTransactionCoordinator policy entry
        // must be one of the enumerated (method, DAO, operation) triples above.
        for (entry in groupEntries) {
            val specs = expected[entry.method]
            assertTrue(
                "GroupTransactionCoordinator must use an exact enumerated (method, DAO, operation), got: " +
                    "${entry.method}/${entry.daos}/${entry.operation}",
                specs != null && specs.any { it.daos == entry.daos.toSet() && it.operation == entry.operation }
            )
        }
    }

    @Test
    fun `ownership — DataRetentionWorker entry has exact DAO and exact operation`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "DataRetentionWorker", methodName = "doWork")
        assertNotNull("DataRetentionWorker entry not found in ownership policy", entry)

        val expectedDaos = setOf("privacyAuditDao")
        assertEquals("DataRetentionWorker DAOs", expectedDaos, entry!!.daos.toSet())
        assertEquals("DataRetentionWorker operation", "insert", entry.operation)
        // Truthful mediated-barrier contract: write protection is provided by
        // WorkerExecutionGuard, not a direct writeBarrier call inside doWork.
        assertEquals("DataRetentionWorker barrier_required", false, entry.barrierRequired)
        assertEquals("DataRetentionWorker barrier_via", "WorkerExecutionGuard", entry.barrierVia)
        assertEquals("owner", "@panospao7", entry.owner)
        assertEquals("linked_issue", "MIT-003", entry.linkedIssue)
        assertTrue("Reason must mention audit or privacy",
            entry.reason.contains("audit", ignoreCase = true) || entry.reason.contains("privacy", ignoreCase = true))
    }

    @Test
    fun `ownership — AiChatRepositoryImpl entries are exactly enumerated with exact DAOs and operations`() {
        val entries = parseEntries(ownershipPolicyFile)
        // Every approved (method, DAO, operation) triple — no wildcard entry.
        // DAO accessor identities: AiChatSessionDao -> aiChatSessionDao,
        // AiChatMessageDao -> aiChatMessageDao.
        data class Spec(val daos: Set<String>, val operation: String, val hint: String)

        val expected = mapOf(
            "createSession" to listOf(
                Spec(setOf("aiChatSessionDao"), "insert", "session")
            ),
            "appendMessage" to listOf(
                Spec(setOf("aiChatMessageDao"), "insert", "message"),
                Spec(setOf("aiChatSessionDao"), "updateLastTouched", "session")
            ),
            "clearSession" to listOf(
                Spec(setOf("aiChatSessionDao"), "deleteById", "session")
            ),
            "clearAllHistory" to listOf(
                Spec(setOf("aiChatSessionDao"), "deleteAll", "session")
            ),
            "purgeOldMessages" to listOf(
                Spec(setOf("aiChatMessageDao"), "deleteOlderThan", "message")
            )
        )

        val aiEntries = entries.filter { it.className == "AiChatRepositoryImpl" }
        val expectedTotal = expected.values.sumOf { it.size }
        assertEquals(
            "AiChatRepositoryImpl must have exactly $expectedTotal entries",
            expectedTotal, aiEntries.size
        )

        for ((methodName, specs) in expected) {
            for (spec in specs) {
                val entry = aiEntries.firstOrNull {
                    it.method == methodName && it.daos.toSet() == spec.daos
                }
                assertNotNull(
                    "AiChatRepositoryImpl.$methodName entry for DAOs ${spec.daos} not found in ownership policy",
                    entry
                )
                assertEntryMetadata(entry!!, spec.operation, spec.daos, true, null, spec.hint)
            }
        }

        // No wildcard / no drift: every AiChatRepositoryImpl policy entry must be
        // one of the enumerated (method, DAO, operation) triples above.
        for (entry in aiEntries) {
            val specs = expected[entry.method]
            assertTrue(
                "AiChatRepositoryImpl must use an exact enumerated (method, DAO, operation), got: " +
                    "${entry.method}/${entry.daos}/${entry.operation}",
                specs != null && specs.any { it.daos == entry.daos.toSet() && it.operation == entry.operation }
            )
        }
    }

    @Test
    fun `ownership — AnomalyAlertRepositoryImpl entry has exact DAO and exact operation`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "AnomalyAlertRepositoryImpl", methodName = "insert")
        assertNotNull("AnomalyAlertRepositoryImpl entry not found in ownership policy", entry)

        val expectedDaos = setOf("anomalyAlertDao")
        assertEquals("AnomalyAlertRepositoryImpl DAOs", expectedDaos, entry!!.daos.toSet())
        assertEquals("AnomalyAlertRepositoryImpl operation", "insert", entry.operation)
        assertEquals("AnomalyAlertRepositoryImpl barrier_required", true, entry.barrierRequired)
        assertEquals("owner", "@panospao7", entry.owner)
        assertEquals("linked_issue", "MIT-003", entry.linkedIssue)
        assertTrue("Reason must mention anomaly or alert",
            entry.reason.contains("anomaly", ignoreCase = true) || entry.reason.contains("alert", ignoreCase = true))
    }

    @Test
    fun `ownership — BusinessExpenseRepository entry has exact DAO and exact operation`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "BusinessExpenseRepository", methodName = "addMileage")
        assertNotNull("BusinessExpenseRepository entry not found in ownership policy", entry)

        val expectedDaos = setOf("mileageTrackingDao")
        assertEquals("BusinessExpenseRepository DAOs", expectedDaos, entry!!.daos.toSet())
        assertEquals("BusinessExpenseRepository operation", "insert", entry.operation)
        assertEquals("BusinessExpenseRepository barrier_required", true, entry.barrierRequired)
        assertEquals("owner", "@panospao7", entry.owner)
        assertEquals("linked_issue", "MIT-003", entry.linkedIssue)
        assertTrue("Reason must mention mileage or business",
            entry.reason.contains("mileage", ignoreCase = true) || entry.reason.contains("business", ignoreCase = true))
    }

    @Test
    fun `ownership — WarrantyExpirationWorker entries are exactly enumerated with exact operations`() {
        val entries = parseEntries(ownershipPolicyFile)
        // Every approved (method, operation) pair — no wildcard entry.
        // doWork writes deliveryDao.recoverStaleClaimed + deliveryDao.deleteOlderThan;
        // private deliverReminder writes insertOrIgnore, claim, markSentFromClaimed,
        // markFailed. All are WorkerExecutionGuard-mediated (barrier_required false).
        data class Spec(val method: String, val operation: String, val hint: String)

        val expected = listOf(
            Spec("doWork", "recoverStaleClaimed", "stale"),
            Spec("doWork", "deleteOlderThan", "prune"),
            Spec("deliverReminder", "insertOrIgnore", "idempotently"),
            Spec("deliverReminder", "claim", "claim"),
            Spec("deliverReminder", "markSentFromClaimed", "SENT"),
            Spec("deliverReminder", "markFailed", "FAILED")
        )

        val workerEntries = entries.filter { it.className == "WarrantyExpirationWorker" }
        assertEquals("WarrantyExpirationWorker must have exactly ${expected.size} entries", expected.size, workerEntries.size)

        for (spec in expected) {
            val entry = workerEntries.firstOrNull { it.method == spec.method && it.operation == spec.operation }
            assertNotNull(
                "WarrantyExpirationWorker.${spec.method} (${spec.operation}) entry not found in ownership policy",
                entry
            )
            assertEquals(
                "WarrantyExpirationWorker.${spec.method} (${spec.operation}) DAOs",
                setOf("warrantyReminderDeliveryDao"), entry!!.daos.toSet()
            )
            assertEquals(
                "WarrantyExpirationWorker.${spec.method} (${spec.operation}) operation",
                spec.operation, entry.operation
            )
            // Truthful mediated-barrier contract: write protection is provided by
            // WorkerExecutionGuard, not a direct writeBarrier call inside the worker.
            assertEquals("WarrantyExpirationWorker barrier_required", false, entry.barrierRequired)
            assertEquals("WarrantyExpirationWorker barrier_via", "WorkerExecutionGuard", entry.barrierVia)
            assertEquals("owner", "@panospao7", entry.owner)
            assertEquals("linked_issue", "MIT-003", entry.linkedIssue)
            assertTrue(
                "WarrantyExpirationWorker.${spec.method} (${spec.operation}) reason must mention '${spec.hint}'",
                entry.reason.contains(spec.hint, ignoreCase = true)
            )
        }

        // No drift: every WarrantyExpirationWorker policy entry must be one of the
        // enumerated (method, operation) pairs above.
        for (entry in workerEntries) {
            assertTrue(
                "WarrantyExpirationWorker must use an exact enumerated (method, operation), got: " +
                    "${entry.method}/${entry.operation}",
                expected.any { it.method == entry.method && it.operation == entry.operation }
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Ownership Batch A — Exchange Rate Store
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `ownership — ExchangeRateStoreAdapter insertOrUpdate entry has exact DAO operation and direct barrier`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "ExchangeRateStoreAdapter", methodName = "insertOrUpdate")
        assertNotNull("ExchangeRateStoreAdapter.insertOrUpdate entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "insertOrUpdate", setOf("exchangeRateDao"), true, null, "exchange")
    }

    @Test
    fun `ownership — ExchangeRateStoreAdapter insertOrUpdateAll entry has exact DAO operation and direct barrier`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "ExchangeRateStoreAdapter", methodName = "insertOrUpdateAll")
        assertNotNull("ExchangeRateStoreAdapter.insertOrUpdateAll entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "insertOrUpdateAll", setOf("exchangeRateDao"), true, null, "exchange")
    }

    @Test
    fun `ownership — ExchangeRateStoreAdapter deleteOldRates entry has exact DAO operation and direct barrier`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "ExchangeRateStoreAdapter", methodName = "deleteOldRates")
        assertNotNull("ExchangeRateStoreAdapter.deleteOldRates entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "deleteOldRates", setOf("exchangeRateDao"), true, null, "exchange")
    }

    @Test
    fun `source evidence — ExchangeRateStoreAdapter checks write barrier before each DAO mutation`() {
        assertBarrierBeforeMutation(
            exchangeRateAdapterSource, "ExchangeRateStoreAdapter", "insertOrUpdate",
            "writeBarrier.checkWritesAllowed(\"ExchangeRateStoreAdapter.insertOrUpdate\")",
            "exchangeRateDao.insertOrUpdate("
        )
        assertBarrierBeforeMutation(
            exchangeRateAdapterSource, "ExchangeRateStoreAdapter", "insertOrUpdateAll",
            "writeBarrier.checkWritesAllowed(\"ExchangeRateStoreAdapter.insertOrUpdateAll\")",
            "exchangeRateDao.insertOrUpdateAll("
        )
        assertBarrierBeforeMutation(
            exchangeRateAdapterSource, "ExchangeRateStoreAdapter", "deleteOldRates",
            "writeBarrier.checkWritesAllowed(\"ExchangeRateStoreAdapter.deleteOldRates\")",
            "exchangeRateDao.deleteOldRates("
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Ownership Batch A — Prompt States
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `ownership — PromptStateRepository recordPrompt entry has exact DAO operation and direct barrier`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "PromptStateRepository", methodName = "recordPrompt")
        assertNotNull("PromptStateRepository.recordPrompt entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "insertPromptState", setOf("promptStateDao"), true, null, "prompt")
    }

    @Test
    fun `ownership — PromptStateRepository recordAcknowledgment entry has exact DAO operation and direct barrier`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "PromptStateRepository", methodName = "recordAcknowledgment")
        assertNotNull("PromptStateRepository.recordAcknowledgment entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "insertPromptState", setOf("promptStateDao"), true, null, "acknowledgment")
    }

    @Test
    fun `ownership — PromptStateRepository cleanupOldRecords entry has exact DAO operation and direct barrier`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "PromptStateRepository", methodName = "cleanupOldRecords")
        assertNotNull("PromptStateRepository.cleanupOldRecords entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "deleteOldPrompts", setOf("promptStateDao"), true, null, "purge")
    }

    @Test
    fun `source evidence — PromptStateRepository checks write barrier before each DAO mutation`() {
        assertBarrierBeforeMutation(
            promptStateRepositorySource, "PromptStateRepository", "recordPrompt",
            "writeBarrier.checkWritesAllowed(\"PromptStateRepository.recordPrompt\")",
            "promptStateDao.insertPromptState("
        )
        assertBarrierBeforeMutation(
            promptStateRepositorySource, "PromptStateRepository", "recordAcknowledgment",
            "writeBarrier.checkWritesAllowed(\"PromptStateRepository.recordAcknowledgment\")",
            "promptStateDao.insertPromptState("
        )
        assertBarrierBeforeMutation(
            promptStateRepositorySource, "PromptStateRepository", "cleanupOldRecords",
            "writeBarrier.checkWritesAllowed(\"PromptStateRepository.cleanupOldRecords\")",
            "promptStateDao.deleteOldPrompts("
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Ownership Batch A — Worker ledger (guard-mediated)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `ownership — WorkerRunLoggerImpl start entry is guard-mediated with exact DAO operation`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "WorkerRunLoggerImpl", methodName = "start")
        assertNotNull("WorkerRunLoggerImpl.start entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "insert", setOf("backgroundJobRunDao"), false, "WorkerExecutionGuard", "RUNNING")
    }

    @Test
    fun `ownership — WorkerRunLogger Handle terminal entry is guard-mediated with exact DAO operation`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "Handle", methodName = "terminal")
        assertNotNull("Handle.terminal entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "completeTerminal", setOf("backgroundJobRunDao"), false, "WorkerExecutionGuard", "terminal")
    }

    @Test
    fun `ownership — WorkerExecutionGuard recoverStaleRunningJobs entry requires direct barrier with exact DAO operation`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "WorkerExecutionGuard", methodName = "recoverStaleRunningJobs")
        assertNotNull("WorkerExecutionGuard.recoverStaleRunningJobs entry not found in ownership policy", entry)

        assertEntryMetadata(entry!!, "staleAbortIfStillRunning", setOf("backgroundJobRunDao"), true, null, "stale")
    }

    @Test
    fun `source evidence — WorkerRunLoggerImpl start inserts run row without direct writeBarrier claim`() {
        val body = extractMethodBody(workerRunLoggerSource, "WorkerRunLoggerImpl", "start")
        assertTrue(
            "WorkerRunLoggerImpl.start must insert a RUNNING row via dao.insert",
            body.contains("dao.insert(")
        )
        assertFalse(
            "WorkerRunLoggerImpl.start must not claim a direct writeBarrier (guard-mediated only)",
            body.contains("writeBarrier.")
        )
    }

    @Test
    fun `source evidence — WorkerExecutionGuard recoverStaleRunningJobs enforces direct barrier before staleAbort`() {
        assertBarrierBeforeMutation(
            workerExecutionGuardSource, "WorkerExecutionGuard", "recoverStaleRunningJobs",
            "writeBarrier.checkWritesAllowed(\"WorkerExecutionGuard.recoverStaleRunningJobs\")",
            "staleAbortIfStillRunning("
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Negative tests — unrelated method/DAO should NOT be present
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `ownership — unrelated class UserCorrectionRepository not present`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "UserCorrectionRepository")
        assertNull("UserCorrectionRepository should NOT be in ownership policy", entry)
    }

    @Test
    fun `ownership — unrelated DAO userCorrectionDao not on DataRetentionWorker`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "DataRetentionWorker", methodName = "doWork")
        assertNotNull("DataRetentionWorker must exist for negative check", entry)
        assertFalse("DataRetentionWorker should NOT list userCorrectionDao",
            entry!!.daos.contains("userCorrectionDao"))
    }

    @Test
    fun `ownership — unrelated DAO expenseDao not on AnomalyAlertRepositoryImpl`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "AnomalyAlertRepositoryImpl", methodName = "insert")
        assertNotNull("AnomalyAlertRepositoryImpl must exist for negative check", entry)
        assertFalse("AnomalyAlertRepositoryImpl should NOT list expenseDao",
            entry!!.daos.contains("expenseDao"))
    }

    @Test
    fun `ownership — unrelated DAO expenseDao not on AiChatRepositoryImpl`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "AiChatRepositoryImpl", methodName = "createSession")
        assertNotNull("AiChatRepositoryImpl.createSession must exist for negative check", entry)
        assertFalse("AiChatRepositoryImpl should NOT list expenseDao",
            entry!!.daos.contains("expenseDao"))
    }

    @Test
    fun `ownership — unrelated DAO receiptDao not on BusinessExpenseRepository`() {
        val entries = parseEntries(ownershipPolicyFile)
        val entry = findEntry(entries, "BusinessExpenseRepository", methodName = "addMileage")
        assertNotNull("BusinessExpenseRepository must exist for negative check", entry)
        assertFalse("BusinessExpenseRepository should NOT list receiptDao",
            entry!!.daos.contains("receiptDao"))
    }

    @Test
    fun `ownership — CategoryRepository is exactly addCategory and deleteCategory`() {
        val entries = parseEntries(ownershipPolicyFile)
        val matching = entries.filter { it.className == "CategoryRepository" }
        assertEquals("Should be exactly 2 CategoryRepository entries", 2, matching.size)

        val addEntry = findEntry(entries, "CategoryRepository", methodName = "addCategory")
        assertNotNull("CategoryRepository.addCategory entry not found in ownership policy", addEntry)
        assertEquals("CategoryRepository.addCategory operation",
            "getOrInsertByNameNoCase", addEntry!!.operation)
        assertEquals("CategoryRepository.addCategory DAOs", setOf("categoryDao"), addEntry.daos.toSet())
        assertEquals("CategoryRepository.addCategory barrier_required", true, addEntry.barrierRequired)

        val deleteEntry = findEntry(entries, "CategoryRepository", methodName = "deleteCategory")
        assertNotNull("CategoryRepository.deleteCategory entry not found in ownership policy", deleteEntry)
        assertEquals("CategoryRepository.deleteCategory operation",
            "delete", deleteEntry!!.operation)
        assertEquals("CategoryRepository.deleteCategory DAOs", setOf("categoryDao"), deleteEntry.daos.toSet())
        assertEquals("CategoryRepository.deleteCategory barrier_required", true, deleteEntry.barrierRequired)
    }

    @Test
    fun `ownership — CategoryRepository merchantCategoryDao writes remain unresolved and unapproved`() {
        val entries = parseEntries(ownershipPolicyFile)
        val categoryEntries = entries.filter { it.className == "CategoryRepository" }
        for (entry in categoryEntries) {
            assertFalse(
                "CategoryRepository.${entry.method} must NOT list merchantCategoryDao " +
                    "(unresolved debt, not authorization)",
                entry.daos.contains("merchantCategoryDao")
            )
        }
        // No approved entry for the seed/normalization write path at all.
        assertNull(
            "No CategoryRepository entry may authorize merchantCategoryDao writes",
            categoryEntries.firstOrNull { it.daos.contains("merchantCategoryDao") }
        )
    }

    @Test
    fun `ownership — ExpenseRepository userCorrectionDao remains unresolved debt, not authorized`() {
        val entries = parseEntries(ownershipPolicyFile)
        val matching = entries.filter { it.className == "ExpenseRepository" }
        assertEquals(
            "ExpenseRepository must NOT appear in the ownership policy " +
                "(userCorrectionDao writes remain unresolved debt)",
            0, matching.size
        )
        // The userCorrectionDao identity must never be approved on any writer.
        for (entry in entries) {
            assertFalse(
                "Entry ${entry.className}.${entry.method} must NOT list userCorrectionDao",
                entry.daos.contains("userCorrectionDao")
            )
        }
    }

    @Test
    fun `ownership — GroupTransactionCoordinator permanentlyDeleteGroup not approved`() {
        val entries = parseEntries(ownershipPolicyFile)
        // permanentlyDeleteGroup only delegates to deleteGroupAtomic, whose body
        // holds the real DAO mutations; the delegating wrapper must NOT be approved.
        val entry = findEntry(entries, "GroupTransactionCoordinator", methodName = "permanentlyDeleteGroup")
        assertNull(
            "GroupTransactionCoordinator.permanentlyDeleteGroup must NOT be an approved writer " +
                "(it delegates to deleteGroupAtomic, which is the approved implementation method)",
            entry
        )
    }

    @Test
    fun `ownership — TransactionLifecycleCoordinator createExpense wrapper not approved`() {
        val entries = parseEntries(ownershipPolicyFile)
        // createExpense is a public wrapper that delegates to createExpenseMutation;
        // only the wrapper-free direct writers are enumerated per policy.
        val entry = findEntry(entries, "TransactionLifecycleCoordinator", methodName = "createExpense")
        assertNull(
            "TransactionLifecycleCoordinator.createExpense must NOT be an approved writer " +
                "(public wrapper; direct DAO mutation happens in createExpenseMutation)",
            entry
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Parser fail-closed tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `parser fails closed on unknown key with entry and path context`() {
        val lines = listOf(
            "entries:",
            "  - path: ExampleWriter.kt",
            "    class: ExampleWriter",
            "    method: \"write\"",
            "    daos: [exampleDao]",
            "    operation: insert",
            "    barrier_required: true",
            "    reason: \"example\"",
            "    owner: \"@panospao7\"",
            "    linked_issue: \"MIT-003\"",
            "    not_a_real_key: value"
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed on an unknown key inside an entry", thrown)
        assertTrue(
            "Failure must identify the unknown key and carry entry/path context",
            thrown!!.message!!.contains("not_a_real_key") &&
                thrown.message!!.contains("ExampleWriter.kt") &&
                thrown.message!!.contains("db_ownership_policy.yml")
        )
    }

    @Test
    fun `parser fails closed when entry is missing required method`() {
        val lines = listOf(
            "entries:",
            "  - path: ExampleWriter.kt",
            "    class: ExampleWriter",
            "    daos: [exampleDao]",
            "    operation: insert",
            "    barrier_required: true",
            "    reason: \"example\"",
            "    owner: \"@panospao7\"",
            "    linked_issue: \"MIT-003\""
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed when an entry has neither method nor method_pattern", thrown)
        assertTrue(
            "Failure must identify the entry and carry path/file context",
            thrown!!.message!!.contains("ExampleWriter.kt") &&
                thrown.message!!.contains("db_ownership_policy.yml")
        )
    }

    @Test
    fun `parser fails closed when entry is missing a required field`() {
        val lines = listOf(
            "entries:",
            "  - path: ExampleWriter.kt",
            "    class: ExampleWriter",
            "    method: \"write\"",
            "    daos: [exampleDao]",
            "    operation: insert",
            "    barrier_required: true",
            "    reason: \"example\"",
            "    owner: \"@panospao7\""
            // linked_issue omitted deliberately
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed when a required field is missing", thrown)
        assertTrue(
            "Failure must name the missing field and the entry",
            thrown!!.message!!.contains("linked_issue") &&
                thrown.message!!.contains("ExampleWriter.kt")
        )
    }

    @Test
    fun `parser fails closed when entry is missing path`() {
        // An ownership entry that omits the '- path:' marker never starts an
        // entry block — every following line is outside an entry and the parser
        // fails closed with the entry content and file context.
        val lines = listOf(
            "entries:",
            "  - class: ExampleWriter",
            "    method: \"write\"",
            "    daos: [exampleDao]",
            "    operation: insert",
            "    barrier_required: true",
            "    reason: \"example\"",
            "    owner: \"@panospao7\"",
            "    linked_issue: \"MIT-003\""
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed when an entry has no path", thrown)
        assertTrue(
            "Failure must identify the entry content and carry file context",
            thrown!!.message!!.contains("ExampleWriter") &&
                thrown.message!!.contains("db_ownership_policy.yml")
        )
    }

    @Test
    fun `parser fails closed when entry is missing operation`() {
        val lines = listOf(
            "entries:",
            "  - path: ExampleWriter.kt",
            "    class: ExampleWriter",
            "    method: \"write\"",
            "    daos: [exampleDao]",
            "    barrier_required: true",
            "    reason: \"example\"",
            "    owner: \"@panospao7\"",
            "    linked_issue: \"MIT-003\""
            // operation omitted deliberately
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed when an entry has no operation", thrown)
        assertTrue(
            "Failure must name the missing field and the entry",
            thrown!!.message!!.contains("operation") &&
                thrown.message!!.contains("ExampleWriter.kt")
        )
    }

    @Test
    fun `parser fails closed on invalid barrier_required boolean with entry context`() {
        val lines = listOf(
            "entries:",
            "  - path: ExampleWriter.kt",
            "    class: ExampleWriter",
            "    method: \"write\"",
            "    daos: [exampleDao]",
            "    operation: insert",
            "    barrier_required: banana",
            "    reason: \"example\"",
            "    owner: \"@panospao7\"",
            "    linked_issue: \"MIT-003\""
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed on an invalid barrier_required value", thrown)
        assertTrue(
            "Failure must identify barrier_required and carry entry/path context",
            thrown!!.message!!.contains("barrier_required") &&
                thrown.message!!.contains("ExampleWriter.kt") &&
                thrown.message!!.contains("db_ownership_policy.yml")
        )
    }

    @Test
    fun `parser fails closed on missing daos with entry context`() {
        val lines = listOf(
            "entries:",
            "  - path: ExampleWriter.kt",
            "    class: ExampleWriter",
            "    method: \"write\"",
            "    operation: insert",
            "    barrier_required: true",
            "    reason: \"example\"",
            "    owner: \"@panospao7\"",
            "    linked_issue: \"MIT-003\""
            // daos intentionally omitted
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed when daos is missing", thrown)
        assertTrue(
            "Failure must identify daos and the entry",
            thrown!!.message!!.contains("daos") &&
                thrown.message!!.contains("ExampleWriter.kt") &&
                thrown.message!!.contains("db_ownership_policy.yml")
        )
    }

    @Test
    fun `parser fails closed on empty daos with entry context`() {
        val lines = listOf(
            "entries:",
            "  - path: ExampleWriter.kt",
            "    class: ExampleWriter",
            "    method: \"write\"",
            "    daos: []",
            "    operation: insert",
            "    barrier_required: true",
            "    reason: \"example\"",
            "    owner: \"@panospao7\"",
            "    linked_issue: \"MIT-003\""
        )
        var thrown: Exception? = null
        try {
            parseEntriesContent(lines, "db_ownership_policy.yml")
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Parser must fail closed when daos is empty", thrown)
        assertTrue(
            "Failure must identify daos and the entry",
            thrown!!.message!!.contains("daos") &&
                thrown.message!!.contains("ExampleWriter.kt") &&
                thrown.message!!.contains("db_ownership_policy.yml")
        )
    }

    @Test
    fun `manifest parser fails closed on unknown key inside a tuple entry`() {
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: 62
            expected:
              - path: app/src/main/java/com/example/SomeClass.kt
                class: SomeClass
                method_pattern: "verify"
                operation: openDatabase
                reason: "example"
                owner: "@test"
                linked_issue: "TEST-001"
                bogus_key: "typo"
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed on an unknown key inside a tuple entry",
            thrown
        )
        assertTrue(
            "Failure must identify the unknown key and carry manifest context",
            thrown!!.message!!.contains("Unknown key") &&
                thrown.message!!.contains("bogus_key") &&
                thrown.message!!.contains(manifest.name)
        )
    }

    @Test
    fun `manifest parser fails closed on unknown key inside counts`() {
        val manifest = writeTempManifest(
            """
            counts:
              bogus_count_key: 5
              structural_entries: 62
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed on an unknown key inside counts",
            thrown
        )
        assertTrue(
            "Failure must identify the unknown counts key",
            thrown!!.message!!.contains("Unknown key") &&
                thrown.message!!.contains("bogus_count_key")
        )
    }

    @Test
    fun `manifest parser fails closed on malformed entry line inside a tuple section`() {
        // A tuple entry that begins with '- class:' instead of '- path:' is a
        // malformed entry and must never be silently ignored.
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: 62
            expected:
              - class: SomeClass
                method_pattern: "verify"
                operation: openDatabase
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed on a malformed entry (no '- path:')",
            thrown
        )
        assertTrue(
            "Failure must carry manifest context",
            thrown!!.message!!.contains("Unexpected line") &&
                thrown.message!!.contains(manifest.name)
        )
    }

    @Test
    fun `manifest parser fails closed when a tuple entry is missing a required field`() {
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: 62
            expected:
              - path: app/src/main/java/com/example/SomeClass.kt
                class: SomeClass
                operation: openDatabase
                reason: "example"
                owner: "@test"
                linked_issue: "TEST-001"
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed when a tuple entry lacks method_pattern",
            thrown
        )
        assertTrue(
            "Failure must name the missing field and carry entry context",
            thrown!!.message!!.contains("method_pattern") &&
                thrown.message!!.contains("SomeClass.kt")
        )
    }

    @Test
    fun `manifest parser fails closed on invalid operation inside a tuple entry`() {
        // Every operation outside the exact whitelist — raw categories, generic
        // `write`, arbitrary names, and the empty value — must fail closed.
        for (invalid in listOf("raw_sqlite", "raw_db_file", "write", "arbitrary", "")) {
            val manifest = writeTempManifest(
                """
                counts:
                  structural_entries: 62
                expected:
                  - path: app/src/main/java/com/example/SomeClass.kt
                    class: SomeClass
                    method_pattern: "verify"
                    operation: $invalid
                    reason: "example"
                    owner: "@test"
                    linked_issue: "TEST-001"
                """.trimIndent()
            )
            var thrown: Exception? = null
            try {
                parseStructuralManifest(manifest)
            } catch (e: Exception) {
                thrown = e
            }
            assertNotNull(
                "Manifest parser must reject operation '$invalid'",
                thrown
            )
            assertTrue(
                "Failure must identify the unsupported operation '$invalid' and the tuple",
                thrown!!.message!!.contains("Unsupported operation") &&
                    thrown.message!!.contains("SomeClass")
            )
        }
    }

    @Test
    fun `manifest parser fails closed on duplicate operation key inside a tuple entry`() {
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: 62
            expected:
              - path: app/src/main/java/com/example/SomeClass.kt
                class: SomeClass
                method_pattern: "verify"
                operation: openDatabase
                operation: writableDatabase
                reason: "example"
                owner: "@test"
                linked_issue: "TEST-001"
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed when a tuple entry repeats 'operation'",
            thrown
        )
        assertTrue(
            "Failure must identify the duplicate key and carry entry context",
            thrown!!.message!!.contains("Duplicate key") &&
                thrown.message!!.contains("operation") &&
                thrown.message!!.contains("SomeClass.kt")
        )
    }

    @Test
    fun `manifest parser fails closed on duplicate counts key`() {
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: 62
              structural_entries: 62
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed when 'counts' repeats a key",
            thrown
        )
        assertTrue(
            "Failure must identify the duplicate counts key",
            thrown!!.message!!.contains("Duplicate counts key") &&
                thrown.message!!.contains("structural_entries")
        )
    }

    @Test
    fun `manifest parser fails closed on duplicate tuple identity in expected`() {
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: 62
            expected:
              - path: app/src/main/java/com/example/SomeClass.kt
                class: SomeClass
                method_pattern: "verify"
                operation: openDatabase
                reason: "first"
                owner: "@test"
                linked_issue: "TEST-001"
              - path: app/src/main/java/com/example/SomeClass.kt
                class: SomeClass
                method_pattern: "verify"
                operation: openDatabase
                reason: "second"
                owner: "@test"
                linked_issue: "TEST-001"
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed when 'expected' repeats a tuple identity",
            thrown
        )
        assertTrue(
            "Failure must identify the duplicate tuple",
            thrown!!.message!!.contains("Duplicate manifest tuple") &&
                thrown.message!!.contains("SomeClass")
        )
    }

    @Test
    fun `manifest parser fails closed on cross-section duplicate tuple identity`() {
        // A tuple identity (path, class, method_pattern, operation) must be
        // unique across the WHOLE manifest: appearing once in `expected` and
        // once in `fixtures` is still a duplicate and must fail closed.
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: 62
            expected:
              - path: app/src/main/java/com/example/SomeClass.kt
                class: SomeClass
                method_pattern: "verify"
                operation: openDatabase
                reason: "first"
                owner: "@test"
                linked_issue: "TEST-001"
            fixtures:
              - path: app/src/main/java/com/example/SomeClass.kt
                class: SomeClass
                method_pattern: "verify"
                operation: openDatabase
                reason: "second"
                owner: "@test"
                linked_issue: "TEST-001"
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed when a tuple identity appears in both expected and fixtures",
            thrown
        )
        assertTrue(
            "Failure must identify the cross-section duplicate tuple",
            thrown!!.message!!.contains("Duplicate manifest tuple") &&
                thrown.message!!.contains("SomeClass")
        )
    }

    @Test
    fun `manifest parser fails closed on blank tuple fields`() {
        // path, class, method_pattern, and operation must all be non-blank.
        // A blank value (including empty quotes) fails closed with field
        // context.  Blank operation is rejected by the exact-operation
        // whitelist (an empty value is never a whitelist member).
        val blankCases = mapOf(
            "path" to "blank",
            "class" to "blank",
            "method_pattern" to "blank",
            "operation" to "Unsupported operation"
        )
        for ((blankKey, expectedFragment) in blankCases) {
            val lines = mutableListOf(
                "counts:",
                "  structural_entries: 62",
                "expected:",
                if (blankKey == "path") "  - path: \"\"" else "  - path: app/src/main/java/com/example/SomeClass.kt",
                if (blankKey == "class") "    class: \"\"" else "    class: SomeClass",
                if (blankKey == "method_pattern") "    method_pattern: \"\"" else "    method_pattern: \"verify\"",
                if (blankKey == "operation") "    operation: \"\"" else "    operation: openDatabase",
                "    reason: \"example\"",
                "    owner: \"@test\"",
                "    linked_issue: \"TEST-001\""
            )
            val manifest = writeTempManifest(lines.joinToString("\n"))
            var thrown: Exception? = null
            try {
                parseStructuralManifest(manifest)
            } catch (e: Exception) {
                thrown = e
            }
            assertNotNull(
                "Manifest parser must fail closed on blank '$blankKey'",
                thrown
            )
            assertTrue(
                "Failure must identify the blank '$blankKey' field",
                thrown!!.message!!.contains(blankKey) &&
                    thrown.message!!.contains(expectedFragment)
            )
        }
    }

    @Test
    fun `manifest parser fails closed on non-canonical tuple path`() {
        val invalidPaths = listOf(
            "SomeClass.kt",
            "src/main/java/com/example/SomeClass.kt",
            "app/src/main/kotlin/com/example/SomeClass.kt",
            "app/src/main/java/com/example/SomeClass",
            "app\\src\\main\\java\\com\\example\\SomeClass.kt",
            "app/src/main/java/../com/example/SomeClass.kt"
        )
        for (path in invalidPaths) {
            val manifest = writeTempManifest(
                """
                counts:
                  structural_entries: 62
                expected:
                  - path: $path
                    class: SomeClass
                    method_pattern: "verify"
                    operation: openDatabase
                    reason: "example"
                    owner: "@test"
                    linked_issue: "TEST-001"
                """.trimIndent()
            )
            var thrown: Exception? = null
            try {
                parseStructuralManifest(manifest)
            } catch (e: Exception) {
                thrown = e
            }
            assertNotNull(
                "Manifest parser must reject non-canonical path '$path'",
                thrown
            )
            assertTrue(
                "Failure must identify the non-canonical path '$path'",
                thrown!!.message!!.contains("canonical")
            )
        }
    }

    @Test
    fun `manifest parser fails closed on wildcard or unbounded method_pattern`() {
        val invalidPatterns = listOf("*", ".*", ".+", "verify*", "verify.*", "verify|execSQL", "[A-Z]+")
        for (pattern in invalidPatterns) {
            val manifest = writeTempManifest(
                """
                counts:
                  structural_entries: 62
                expected:
                  - path: app/src/main/java/com/example/SomeClass.kt
                    class: SomeClass
                    method_pattern: "$pattern"
                    operation: openDatabase
                    reason: "example"
                    owner: "@test"
                    linked_issue: "TEST-001"
                """.trimIndent()
            )
            var thrown: Exception? = null
            try {
                parseStructuralManifest(manifest)
            } catch (e: Exception) {
                thrown = e
            }
            assertNotNull(
                "Manifest parser must reject method_pattern '$pattern'",
                thrown
            )
            assertTrue(
                "Failure must identify the invalid method_pattern '$pattern'",
                thrown!!.message!!.contains("method_pattern")
            )
        }
    }

    @Test
    fun `manifest parser fails closed on operation outside the exact whitelist`() {
        // Beyond the raw_/generic coverage in the other negative test, a
        // wrong-case operation and a method-call shaped value are also not
        // exact whitelist members and must fail closed.
        for (invalid in listOf("execsql", "OpenDatabase", "openDatabase()")) {
            val manifest = writeTempManifest(
                """
                counts:
                  structural_entries: 62
                expected:
                  - path: app/src/main/java/com/example/SomeClass.kt
                    class: SomeClass
                    method_pattern: "verify"
                    operation: $invalid
                    reason: "example"
                    owner: "@test"
                    linked_issue: "TEST-001"
                """.trimIndent()
            )
            var thrown: Exception? = null
            try {
                parseStructuralManifest(manifest)
            } catch (e: Exception) {
                thrown = e
            }
            assertNotNull(
                "Manifest parser must reject operation '$invalid'",
                thrown
            )
            assertTrue(
                "Failure must identify the unsupported operation '$invalid'",
                thrown!!.message!!.contains("Unsupported operation") &&
                    thrown.message!!.contains("SomeClass")
            )
        }
    }

    @Test
    fun `manifest parser fails closed on negative count`() {
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: -5
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Manifest parser must reject a negative count", thrown)
        assertTrue(
            "Failure must identify the negative count",
            thrown!!.message!!.contains("structural_entries") &&
                thrown.message!!.contains("negative")
        )
    }

    @Test
    fun `manifest parser fails closed on non-integer count`() {
        val manifest = writeTempManifest(
            """
            counts:
              structural_entries: sixty-two
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Manifest parser must reject a non-integer count", thrown)
        assertTrue(
            "Failure must identify the non-integer count",
            thrown!!.message!!.contains("structural_entries") &&
                thrown.message!!.contains("integer")
        )
    }

    @Test
    fun `manifest parser fails closed when a required count is missing`() {
        val manifest = writeTempManifest(
            """
            counts:
            """.trimIndent()
        )
        var thrown: Exception? = null
        try {
            parseStructuralManifest(manifest)
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(
            "Manifest parser must fail closed when a required count is missing",
            thrown
        )
        assertTrue(
            "Failure must identify the missing count key",
            thrown!!.message!!.contains("structural_entries")
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Cross-cutting guard tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `all structural exception entries have class field matching file stem`() {
        val entries = parseEntries(structuralExceptionsFile)
        for (entry in entries) {
            // Paths are canonical repository-relative POSIX paths
            // (e.g. app/src/main/java/.../DatabaseMigrations.kt); the class must
            // match the file stem, not the full path.
            val expectedClass = entry.path.substringAfterLast('/').removeSuffix(".kt")
            assertEquals(
                "Structural exception $expectedClass: path stem must match class",
                expectedClass, entry.className
            )
        }
    }

    @Test
    fun `all ownership policy entries have non-empty daos`() {
        val entries = parseEntries(ownershipPolicyFile)
        for (entry in entries) {
            assertTrue(
                "Ownership entry ${entry.className} must have at least one DAO",
                entry.daos.isNotEmpty()
            )
        }
    }

    @Test
    fun `no ownership entry uses wildcard symbol field`() {
        val entries = parseEntries(ownershipPolicyFile)
        for (entry in entries) {
            assertNotEquals("class field must not be wildcard '*' for ${entry.path}", "*", entry.className)
            assertNotEquals("path field must not be wildcard '*'", "*", entry.path)
        }
    }

    @Test
    fun `no structural exception entry uses wildcard symbol field for class`() {
        val entries = parseEntries(structuralExceptionsFile)
        for (entry in entries) {
            assertNotEquals("class field must not be '*' for ${entry.path}", "*", entry.className)
        }
    }
}
