package com.yourname.expensetracker.data.backup

import android.database.sqlite.SQLiteDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.DEPOSIT_TYPE_SQL
import com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.EFFECTIVE_AMOUNT_SQL
import com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.SPENDING_TYPE_SQL
import com.yourname.expensetracker.data.database.query.SpendingInclusionSql
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

// ─────────────────────────────────────────────────────────────────────────────
// Restore semantic verification (UF-46): aggregate fingerprints.
//
// A fixed, code-owned registry of four aggregates is captured from the final snapshot at backup
// time (stored in the encrypted manifest) and recomputed at restore checkpoints. All SQL comes from
// the registry below; no manifest string is ever executed or interpolated (INV-1). Everything here
// is read-only and takes an already-open handle (INV-16): there is no open call in this file.
//
// Diagnostics and exception text built from these types carry bounded codes only (INV-14): a
// SemanticCode, an aggregate id, a checkpoint and a bucket count. Never an amount, sum, currency,
// type text, SQL or Throwable message.
// ─────────────────────────────────────────────────────────────────────────────

/** Closed registry of aggregate ids (INV-1). Declaration order is the registry order (INV-15). */
enum class SemanticAggregateId {
    /** Every expenses row, no filter. Key: stored type text + stored currency text. Value: raw amount. */
    EXPENSE_RAW_BY_TYPE_CURRENCY,

    /** Spending rows by currency, using the canonical spending-inclusion effective amount (D-SIS). */
    SPENDING_EFFECTIVE_BY_CURRENCY,

    /** Deposit rows by currency, using the canonical effective amount. */
    INCOME_EFFECTIVE_BY_CURRENCY,

    /** Every manual recurring rule. Key: active flag + stored currency text. Value: raw amount. */
    RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY
}

/** Verification checkpoints (spec section 1). */
enum class SemanticCheckpoint {
    C0_BACKUP_SELF_CHECK,
    C1_STAGED_SOURCE,
    C2_STAGED_POST_MIGRATION,
    C3_LIVE_POST_SWAP
}

/**
 * Bounded semantic codes. Used only as the `semanticCode` metadata value and as exception codes.
 * Deliberately NOT part of `DiagnosticReasonCode`.
 */
enum class SemanticCode {
    SEMANTIC_VERIFIED,
    SEMANTIC_MISMATCH,
    SEMANTIC_QUERY_FAILED,
    SEMANTIC_MANIFEST_MALFORMED,
    SEMANTIC_CAPTURE_FAILED,
    SEMANTIC_SELF_CHECK_FAILED,
    SEMANTIC_BUCKET_LIMIT,
    SEMANTIC_NOT_VERIFIABLE_LEGACY,
    SEMANTIC_NOT_VERIFIABLE_VERSION,
    SEMANTIC_NOT_VERIFIABLE_NOT_CAPTURED,
    SEMANTIC_NOT_VERIFIABLE_DEFINITION_CHANGED,
    SEMANTIC_NOT_VERIFIABLE_NO_MANIFEST;

    companion object {
        /** Maps the domain-local reason onto the data-layer code. */
        fun forNotVerifiable(reason: SemanticNotVerifiableReason): SemanticCode = when (reason) {
            SemanticNotVerifiableReason.LEGACY -> SEMANTIC_NOT_VERIFIABLE_LEGACY
            SemanticNotVerifiableReason.VERSION -> SEMANTIC_NOT_VERIFIABLE_VERSION
            SemanticNotVerifiableReason.NOT_CAPTURED -> SEMANTIC_NOT_VERIFIABLE_NOT_CAPTURED
            SemanticNotVerifiableReason.DEFINITION_CHANGED -> SEMANTIC_NOT_VERIFIABLE_DEFINITION_CHANGED
            SemanticNotVerifiableReason.NO_MANIFEST -> SEMANTIC_NOT_VERIFIABLE_NO_MANIFEST
        }
    }
}

enum class SemanticCaptureStatus { CAPTURED, NOT_CAPTURED }

/** Why a section is NOT_CAPTURED (a subset of [SemanticCode]). */
enum class SemanticNotCapturedReason {
    SEMANTIC_CAPTURE_FAILED,
    SEMANTIC_SELF_CHECK_FAILED,
    SEMANTIC_BUCKET_LIMIT;

    val code: SemanticCode
        get() = when (this) {
            SEMANTIC_CAPTURE_FAILED -> SemanticCode.SEMANTIC_CAPTURE_FAILED
            SEMANTIC_SELF_CHECK_FAILED -> SemanticCode.SEMANTIC_SELF_CHECK_FAILED
            SEMANTIC_BUCKET_LIMIT -> SemanticCode.SEMANTIC_BUCKET_LIMIT
        }
}

/**
 * One bucket of an aggregate.
 *
 * - [key]: the raw stored text of the key columns (case-sensitive).
 * - [n]: row count.
 * - [u]: rows whose value was NULL or non-finite (never added to [s]).
 * - [s]: exact sum of the finite values, built from each SQLite REAL through `BigDecimal(double)`
 *   (the exact binary expansion). No rounding happens anywhere.
 *
 * Equality compares [s] with `compareTo` (scale-insensitive), so 32.5 equals 32.50.
 */
class AggregateBucket(
    val key: List<String>,
    val n: Int,
    val u: Int,
    val s: BigDecimal
) {
    override fun equals(other: Any?): Boolean =
        other is AggregateBucket && key == other.key && n == other.n && u == other.u && s.compareTo(other.s) == 0

    override fun hashCode(): Int {
        val normalised = if (s.signum() == 0) BigDecimal.ZERO else s.stripTrailingZeros()
        return 31 * (31 * (31 * key.hashCode() + n) + u) + normalised.hashCode()
    }

    // Bounded on purpose: no key text and no sum in any rendering of this type.
    override fun toString(): String = "AggregateBucket(n=$n, u=$u)"

    internal fun toJson(): JSONObject = JSONObject().apply {
        val keyArray = JSONArray()
        for (element in key) keyArray.put(element)
        put("key", keyArray)
        put("n", n)
        put("u", u)
        put("s", s.toPlainString()) // always a JSON string, never a number
    }
}

/** One aggregate: the hash of the definition it was computed with, plus its buckets. */
class AggregateEntry(
    val definitionHash: String,
    val buckets: List<AggregateBucket>
) {
    override fun equals(other: Any?): Boolean =
        other is AggregateEntry && definitionHash == other.definitionHash && buckets == other.buckets

    override fun hashCode(): Int = 31 * definitionHash.hashCode() + buckets.hashCode()

    override fun toString(): String = "AggregateEntry(buckets=${buckets.size})"
}

/**
 * The manifest section. [aggregates] is empty exactly when [status] is NOT_CAPTURED.
 * For an unknown [version] the content is never interpreted (the parser leaves it empty).
 */
class SemanticAggregateSet(
    val version: Int,
    val status: SemanticCaptureStatus,
    val notCapturedReason: SemanticNotCapturedReason?,
    val aggregates: Map<SemanticAggregateId, AggregateEntry>
) {
    override fun equals(other: Any?): Boolean =
        other is SemanticAggregateSet && version == other.version && status == other.status &&
            notCapturedReason == other.notCapturedReason && aggregates == other.aggregates

    override fun hashCode(): Int =
        31 * (31 * (31 * version + status.hashCode()) + (notCapturedReason?.hashCode() ?: 0)) + aggregates.hashCode()

    override fun toString(): String =
        "SemanticAggregateSet(version=$version, status=$status, reason=$notCapturedReason, aggregates=${aggregates.size})"

    /** Serialises the section. Sums are JSON strings; bucket order is deterministic. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("status", status.name)
        notCapturedReason?.let { put("notCapturedReason", it.name) }
        val aggregatesObject = JSONObject()
        for (id in SemanticAggregateId.values()) {
            val entry = aggregates[id] ?: continue
            val bucketArray = JSONArray()
            for (bucket in entry.buckets) bucketArray.put(bucket.toJson())
            aggregatesObject.put(
                id.name,
                JSONObject().apply {
                    put("definitionHash", entry.definitionHash)
                    put("buckets", bucketArray)
                }
            )
        }
        put("aggregates", aggregatesObject)
    }

    companion object {
        fun ofCaptured(aggregates: Map<SemanticAggregateId, AggregateEntry>): SemanticAggregateSet =
            SemanticAggregateSet(
                version = SemanticAggregates.SECTION_VERSION,
                status = SemanticCaptureStatus.CAPTURED,
                notCapturedReason = null,
                aggregates = aggregates
            )

        fun ofNotCaptured(reason: SemanticNotCapturedReason): SemanticAggregateSet =
            SemanticAggregateSet(
                version = SemanticAggregates.SECTION_VERSION,
                status = SemanticCaptureStatus.NOT_CAPTURED,
                notCapturedReason = reason,
                aggregates = emptyMap()
            )
    }
}

/** Result of parsing a manifest section (INV-12). The parser never throws because of the section. */
sealed interface SemanticParseResult {
    /** A structurally valid section (also returned for an unknown version, which is never interpreted). */
    class Parsed(val set: SemanticAggregateSet) : SemanticParseResult

    /** A structurally invalid section at a known version. Restore is blocked before any destructive step. */
    class Malformed(val code: SemanticCode = SemanticCode.SEMANTIC_MANIFEST_MALFORMED) : SemanticParseResult
}

/** What a checkpoint compares against (the outcome of [BackupVerifier.expectedAggregatesAfterMigration]). */
sealed interface SemanticBasis {
    /**
     * Compare against [set]. Aggregates in [definitionChanged] are reported as DEFINITION_CHANGED instead
     * of compared (a declared later migration invalidated them, INV-9).
     */
    class Compare(
        val set: SemanticAggregateSet,
        val definitionChanged: Set<SemanticAggregateId> = emptySet()
    ) : SemanticBasis

    class NotVerifiable(val reason: SemanticNotVerifiableReason) : SemanticBasis

    /** A malformed section reached the verifier: fails closed. */
    class Malformed(val code: SemanticCode = SemanticCode.SEMANTIC_MANIFEST_MALFORMED) : SemanticBasis
}

/** The typed, closed input of the semantic pass of `BackupVerifier.verify` / `verifyQuick`. */
class SemanticExpectation(
    val checkpoint: SemanticCheckpoint,
    val basis: SemanticBasis
)

/**
 * Outcome of one checkpoint. Bounded: only codes, an aggregate id, the checkpoint and a bucket count.
 */
sealed interface SemanticCheckResult {
    val checkpoint: SemanticCheckpoint

    /** All four aggregates matched. */
    class Verified(override val checkpoint: SemanticCheckpoint) : SemanticCheckResult

    /** Nothing mismatched, but at least one aggregate (or the whole section) could not be compared. */
    class NotVerifiable(
        override val checkpoint: SemanticCheckpoint,
        val reason: SemanticNotVerifiableReason
    ) : SemanticCheckResult

    /**
     * Fail-closed. [code] is MISMATCH, QUERY_FAILED or MANIFEST_MALFORMED. [mismatchedBuckets] counts the
     * buckets that differ (present on one side only, or with a different n, u or s) for [aggregateId].
     */
    class Failed(
        override val checkpoint: SemanticCheckpoint,
        val code: SemanticCode,
        val aggregateId: SemanticAggregateId?,
        val mismatchedBuckets: Int
    ) : SemanticCheckResult
}

/** Registry, capture, parse and compare for the semantic aggregates. */
object SemanticAggregates {

    /** Section version. Adding an aggregate id requires a bump. */
    const val SECTION_VERSION: Int = 1

    /** At most this many buckets per aggregate (capture: NOT_CAPTURED above it; parse: malformed). */
    const val MAX_BUCKETS_PER_AGGREGATE: Int = 256

    /** Parse limit for one key element. */
    const val MAX_KEY_ELEMENT_LENGTH: Int = 64

    /** Parse limit for the text of one sum. */
    const val MAX_SUM_TEXT_LENGTH: Int = 2048

    /** Parse limit for a definition hash. */
    private const val MAX_HASH_LENGTH: Int = 64

    /**
     * The spending aggregate reads the spending-exclusion column added by schema 152 (D-SIS). A manifest
     * whose source schema is older cannot carry a comparable section: NOT_VERIFIABLE(VERSION).
     */
    const val MIN_SOURCE_SCHEMA_VERSION: Int = 152

    /**
     * Migrations (keyed by their END version) that write or rebuild a fingerprinted table, with the
     * aggregates they invalidate for sources below that version (INV-9). Empty today: the 151 to 152
     * migration only adds tables and one nullable column, which is identity for every fingerprinted
     * value. Any later migration that writes a fingerprinted table must add an entry here, otherwise the
     * migration chain test fails.
     */
    val DECLARED_MIGRATION_INVALIDATIONS: Map<Int, Set<SemanticAggregateId>> = emptyMap()

    private val PLAIN_DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?")

    // ── Registry ──────────────────────────────────────────────────────────

    /**
     * Code-owned definition of one aggregate. The filter and value fragments are the canonical
     * constants, referenced by symbol and used unchanged; nothing is copied or added.
     */
    internal class AggregateDefinition(
        val id: SemanticAggregateId,
        val table: String,
        val keyColumns: List<String>,
        val filterSql: String?,
        val valueSql: String
    ) {
        /** Row-level query: key columns as text, then the value. Aggregation happens in Kotlin (exact sums). */
        val querySql: String =
            "SELECT " + keyColumns.joinToString(", ") { "CAST($it AS TEXT)" } + ", " + valueSql +
                " FROM " + table + (filterSql?.let { " WHERE $it" } ?: "")

        /** First 16 hex characters of SHA-256 over the canonical definition text. Contains no user data. */
        val definitionHash: String = sha256Hex16(
            "v$SECTION_VERSION|${id.name}|$table|keys=${keyColumns.joinToString(",")}|" +
                "filter=${filterSql ?: ""}|value=$valueSql"
        )
    }

    internal val registry: List<AggregateDefinition> = listOf(
        AggregateDefinition(
            id = SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
            table = "expenses",
            keyColumns = listOf("transactionType", "currency"),
            filterSql = null,
            valueSql = "amount"
        ),
        AggregateDefinition(
            id = SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY,
            table = "expenses",
            keyColumns = listOf("currency"),
            filterSql = SPENDING_TYPE_SQL,
            valueSql = SpendingInclusionSql.EFFECTIVE_AMOUNT
        ),
        AggregateDefinition(
            id = SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
            table = "expenses",
            keyColumns = listOf("currency"),
            filterSql = DEPOSIT_TYPE_SQL,
            valueSql = EFFECTIVE_AMOUNT_SQL
        ),
        AggregateDefinition(
            id = SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY,
            table = "manual_recurring_expenses",
            keyColumns = listOf("isActive", "currency"),
            filterSql = null,
            valueSql = "amount"
        )
    )

    /**
     * Definition hashes written by earlier builds that stay comparable (INV-10). Refund netting changed
     * the text of the spending value expression; a manifest written before it carries the old hash. The
     * old and new expressions are equal on every row with `refundNettedAmount = 0.0`, which is every
     * row of a database that was migrated up from schema 152 or below, so such a manifest is compared
     * (not reported DEFINITION_CHANGED). The legacy value text is the frozen
     * [SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING]. The income aggregate keeps the inner
     * constant and did not change.
     */
    private val LEGACY_DEFINITION_HASHES: Map<SemanticAggregateId, Set<String>> = mapOf(
        SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY to setOf(
            AggregateDefinition(
                id = SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY,
                table = "expenses",
                keyColumns = listOf("currency"),
                filterSql = SPENDING_TYPE_SQL,
                valueSql = SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING
            ).definitionHash
        )
    )

    /** True when [hash] is the current definition hash of [id] or one of its accepted legacy hashes. */
    internal fun isAcceptedDefinitionHash(id: SemanticAggregateId, hash: String): Boolean =
        hash == definitionOf(id).definitionHash || hash in (LEGACY_DEFINITION_HASHES[id] ?: emptySet())

    /** The current definition hash of [id]. */
    fun definitionHash(id: SemanticAggregateId): String = definitionOf(id).definitionHash

    /** Tables whose rows are fingerprinted (the migration chain test checks writes to these). */
    fun fingerprintedTables(): Set<String> = registry.mapTo(linkedSetOf()) { it.table }

    internal fun definitionOf(id: SemanticAggregateId): AggregateDefinition =
        registry.first { it.id == id }

    // ── Capture ───────────────────────────────────────────────────────────

    /**
     * Captures all four aggregates from [db] (an already-open, read-only snapshot handle).
     *
     * Never fails the backup (INV-5): any non-cancellation problem yields a NOT_CAPTURED set with a
     * bounded reason: a query error or a conservation violation is SEMANTIC_CAPTURE_FAILED and more than
     * [MAX_BUCKETS_PER_AGGREGATE] buckets is SEMANTIC_BUCKET_LIMIT. CancellationException is rethrown.
     *
     * Conservation (INV-2): the bucket counts of the raw expense aggregate must add up to
     * `tableCounts["expenses"]`, and those of the recurring aggregate to
     * `tableCounts["manual_recurring_expenses"]`.
     */
    fun capture(db: SQLiteDatabase, tableCounts: Map<String, Int>): SemanticAggregateSet {
        return try {
            val aggregates = LinkedHashMap<SemanticAggregateId, AggregateEntry>()
            for (definition in registry) {
                aggregates[definition.id] = AggregateEntry(definition.definitionHash, readBuckets(db, definition))
            }
            if (!conservationHolds(aggregates, tableCounts)) {
                SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)
            } else {
                SemanticAggregateSet.ofCaptured(aggregates)
            }
        } catch (e: BucketLimitExceeded) {
            SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_BUCKET_LIMIT)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)
        }
    }

    private fun conservationHolds(
        aggregates: Map<SemanticAggregateId, AggregateEntry>,
        tableCounts: Map<String, Int>
    ): Boolean {
        val expenseRows = tableCounts["expenses"] ?: return false
        val recurringRows = tableCounts["manual_recurring_expenses"] ?: return false
        return rowCount(aggregates[SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY]) == expenseRows.toLong() &&
            rowCount(aggregates[SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY]) == recurringRows.toLong()
    }

    private fun rowCount(entry: AggregateEntry?): Long =
        entry?.buckets?.sumOf { it.n.toLong() } ?: -1L

    /** Internal marker: an aggregate exceeded [MAX_BUCKETS_PER_AGGREGATE]. */
    private class BucketLimitExceeded : RuntimeException()

    private class BucketAccumulator {
        var n: Int = 0
        var u: Int = 0
        var s: BigDecimal = BigDecimal.ZERO
    }

    /**
     * Reads one aggregate row by row and sums exactly in Kotlin (never SQL `SUM`, whose REAL result can
     * differ between SQLite versions). NULL is checked before the value is read; NULL and non-finite
     * values count in `u` and never in `s`.
     */
    private fun readBuckets(db: SQLiteDatabase, definition: AggregateDefinition): List<AggregateBucket> {
        val keyWidth = definition.keyColumns.size
        val accumulators = LinkedHashMap<List<String>, BucketAccumulator>()
        db.rawQuery(definition.querySql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = ArrayList<String>(keyWidth)
                for (index in 0 until keyWidth) {
                    check(!cursor.isNull(index)) { "SEMANTIC_KEY_UNAVAILABLE" }
                    key.add(cursor.getString(index))
                }
                var accumulator = accumulators[key]
                if (accumulator == null) {
                    if (accumulators.size >= MAX_BUCKETS_PER_AGGREGATE) throw BucketLimitExceeded()
                    accumulator = BucketAccumulator()
                    accumulators[key] = accumulator
                }
                accumulator.n += 1
                if (cursor.isNull(keyWidth)) {
                    accumulator.u += 1
                } else {
                    val value = cursor.getDouble(keyWidth)
                    if (value.isNaN() || value.isInfinite()) {
                        accumulator.u += 1
                    } else {
                        accumulator.s = accumulator.s.add(BigDecimal(value)) // exact binary expansion
                    }
                }
            }
        }
        return accumulators.entries
            .sortedWith { a, b -> compareKeys(a.key, b.key) }
            .map { (key, accumulator) -> AggregateBucket(key, accumulator.n, accumulator.u, accumulator.s) }
    }

    private fun compareKeys(a: List<String>, b: List<String>): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            val c = a[index].compareTo(b[index])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    // ── Parse ─────────────────────────────────────────────────────────────

    /**
     * Parses a manifest section (INV-12). Never throws (CancellationException excepted).
     *
     * An unknown [SECTION_VERSION] yields a [SemanticParseResult.Parsed] set with that version and no
     * aggregates: the content of an unknown version is never interpreted. At the known version, any of
     * the following is [SemanticParseResult.Malformed]: CAPTURED without exactly the four registered ids,
     * an unknown id, NOT_CAPTURED with aggregates present, a wrong JSON type (including a numeric `s`),
     * a negative `n` or `u`, an `s` that is not plain decimal text, a wrong key arity, a duplicate key,
     * or an exceeded limit (256 buckets, 64 characters per key element, 2048 characters per `s`).
     */
    fun parse(section: JSONObject): SemanticParseResult {
        return try {
            SemanticParseResult.Parsed(parseOrThrow(section))
        } catch (e: MalformedSection) {
            SemanticParseResult.Malformed()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SemanticParseResult.Malformed()
        }
    }

    private class MalformedSection : RuntimeException()

    private fun malformed(): Nothing = throw MalformedSection()

    private fun parseOrThrow(section: JSONObject): SemanticAggregateSet {
        val version = intOrNull(section.opt("version")) ?: malformed()
        if (version != SECTION_VERSION) {
            return SemanticAggregateSet(
                version = version,
                status = SemanticCaptureStatus.NOT_CAPTURED,
                notCapturedReason = null,
                aggregates = emptyMap()
            )
        }
        val status = when (section.opt("status")) {
            "CAPTURED" -> SemanticCaptureStatus.CAPTURED
            "NOT_CAPTURED" -> SemanticCaptureStatus.NOT_CAPTURED
            else -> malformed()
        }
        val rawAggregates = section.opt("aggregates")
        val aggregatesObject: JSONObject? = when {
            rawAggregates == null || rawAggregates === JSONObject.NULL -> null
            rawAggregates is JSONObject -> rawAggregates
            else -> malformed()
        }
        if (status == SemanticCaptureStatus.NOT_CAPTURED) {
            if (aggregatesObject != null && aggregatesObject.length() > 0) malformed()
            val rawReason = section.opt("notCapturedReason") as? String
            // An unknown or missing reason is tolerated: the restore is NOT_VERIFIABLE(NOT_CAPTURED) either way.
            val reason = SemanticNotCapturedReason.values().firstOrNull { it.name == rawReason }
            return SemanticAggregateSet(version, status, reason, emptyMap())
        }
        if (aggregatesObject == null) malformed()
        val ids = LinkedHashSet<SemanticAggregateId>()
        for (name in aggregatesObject.keys()) {
            val id = SemanticAggregateId.values().firstOrNull { it.name == name } ?: malformed()
            ids.add(id)
        }
        if (ids.size != SemanticAggregateId.values().size) malformed()
        val aggregates = LinkedHashMap<SemanticAggregateId, AggregateEntry>()
        for (definition in registry) {
            val entryObject = aggregatesObject.opt(definition.id.name) as? JSONObject ?: malformed()
            aggregates[definition.id] = parseEntry(entryObject, definition.keyColumns.size)
        }
        return SemanticAggregateSet(version, status, null, aggregates)
    }

    private fun parseEntry(entry: JSONObject, keyWidth: Int): AggregateEntry {
        val hash = entry.opt("definitionHash") as? String ?: malformed()
        if (hash.isEmpty() || hash.length > MAX_HASH_LENGTH) malformed()
        val bucketArray = entry.opt("buckets") as? JSONArray ?: malformed()
        if (bucketArray.length() > MAX_BUCKETS_PER_AGGREGATE) malformed()
        val seenKeys = HashSet<List<String>>()
        val buckets = ArrayList<AggregateBucket>(bucketArray.length())
        for (index in 0 until bucketArray.length()) {
            val bucketObject = bucketArray.opt(index) as? JSONObject ?: malformed()
            val keyArray = bucketObject.opt("key") as? JSONArray ?: malformed()
            if (keyArray.length() != keyWidth) malformed()
            val key = ArrayList<String>(keyWidth)
            for (keyIndex in 0 until keyWidth) {
                val element = keyArray.opt(keyIndex) as? String ?: malformed()
                if (element.length > MAX_KEY_ELEMENT_LENGTH) malformed()
                key.add(element)
            }
            if (!seenKeys.add(key)) malformed()
            val n = intOrNull(bucketObject.opt("n")) ?: malformed()
            val u = intOrNull(bucketObject.opt("u")) ?: malformed()
            if (n < 0 || u < 0) malformed()
            val sumText = bucketObject.opt("s") as? String ?: malformed() // a JSON number is rejected
            if (sumText.length > MAX_SUM_TEXT_LENGTH || !PLAIN_DECIMAL.matches(sumText)) malformed()
            buckets.add(AggregateBucket(key, n, u, BigDecimal(sumText)))
        }
        return AggregateEntry(hash, buckets)
    }

    private fun intOrNull(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
        else -> null
    }

    // ── Compare ───────────────────────────────────────────────────────────

    /**
     * Compares two sets (used by the C0 self-check after a serialise / parse round trip).
     *
     * Per INV-10 the expected hash is checked against the CURRENT registry hash, not against [actual]. An
     * aggregate missing from [actual] is reported as SEMANTIC_QUERY_FAILED (unavailable, never a pass).
     */
    fun compare(
        expected: SemanticAggregateSet,
        actual: SemanticAggregateSet,
        checkpoint: SemanticCheckpoint
    ): SemanticCheckResult = evaluate(checkpoint, expected, emptySet()) { definition ->
        actual.aggregates[definition.id]?.buckets ?: error("SEMANTIC_AGGREGATE_UNAVAILABLE")
    }

    /**
     * Recomputes the aggregates from [db] (an already-open, read-only handle) and compares them to the
     * expectation. A query failure is SEMANTIC_QUERY_FAILED (fail-closed, never NOT_VERIFIABLE).
     * CancellationException is rethrown. This function opens nothing and writes nothing.
     */
    fun verifyAgainst(db: SQLiteDatabase, expectation: SemanticExpectation): SemanticCheckResult {
        val checkpoint = expectation.checkpoint
        return when (val basis = expectation.basis) {
            is SemanticBasis.NotVerifiable -> SemanticCheckResult.NotVerifiable(checkpoint, basis.reason)
            is SemanticBasis.Malformed -> SemanticCheckResult.Failed(checkpoint, basis.code, null, 0)
            is SemanticBasis.Compare ->
                evaluate(checkpoint, basis.set, basis.definitionChanged) { definition -> readBuckets(db, definition) }
        }
    }

    private fun evaluate(
        checkpoint: SemanticCheckpoint,
        expected: SemanticAggregateSet,
        definitionChanged: Set<SemanticAggregateId>,
        actualBuckets: (AggregateDefinition) -> List<AggregateBucket>
    ): SemanticCheckResult {
        var firstNotVerifiable: SemanticNotVerifiableReason? = null
        for (definition in registry) {
            val id = definition.id
            val entry = expected.aggregates[id]
                ?: return SemanticCheckResult.Failed(checkpoint, SemanticCode.SEMANTIC_MANIFEST_MALFORMED, id, 0)
            if (id in definitionChanged || !isAcceptedDefinitionHash(id, entry.definitionHash)) {
                if (firstNotVerifiable == null) firstNotVerifiable = SemanticNotVerifiableReason.DEFINITION_CHANGED
                continue
            }
            val actual = try {
                actualBuckets(definition)
            } catch (e: BucketLimitExceeded) {
                // The expectation holds at most 256 buckets, so a larger actual aggregate cannot match.
                return SemanticCheckResult.Failed(
                    checkpoint, SemanticCode.SEMANTIC_MISMATCH, id, MAX_BUCKETS_PER_AGGREGATE + 1
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return SemanticCheckResult.Failed(checkpoint, SemanticCode.SEMANTIC_QUERY_FAILED, id, 0)
            }
            val mismatched = countMismatchedBuckets(entry.buckets, actual)
            if (mismatched > 0) {
                return SemanticCheckResult.Failed(checkpoint, SemanticCode.SEMANTIC_MISMATCH, id, mismatched)
            }
        }
        return firstNotVerifiable?.let { SemanticCheckResult.NotVerifiable(checkpoint, it) }
            ?: SemanticCheckResult.Verified(checkpoint)
    }

    /** Buckets present on one side only, or with a different n, u or s (zero tolerance). */
    private fun countMismatchedBuckets(expected: List<AggregateBucket>, actual: List<AggregateBucket>): Int {
        val expectedByKey = expected.associateBy { it.key }
        val actualByKey = actual.associateBy { it.key }
        var mismatched = 0
        for (key in expectedByKey.keys + actualByKey.keys) {
            if (expectedByKey[key] != actualByKey[key]) mismatched += 1
        }
        return mismatched
    }

    private fun sha256Hex16(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
