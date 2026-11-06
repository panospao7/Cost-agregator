package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.query.SpendingInclusionSql
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

/**
 * UF-46 / S1: static and structural pins of the semantic aggregate registry.
 *
 * Covers INV-1 (closed code-owned registry, SQL-keyed API deleted), INV-2 (spending / income use the
 * canonical constants by symbol, no type literal, no added predicate), INV-3 and INV-4 (no SUM, no
 * conversion), INV-9 (no undeclared write to a fingerprinted table in migrations 145..152), INV-10
 * (definition hash shape), INV-12 (adding an id needs a version bump), INV-14 (bounded renderings) and
 * INV-16 (read-only, no open call). AE-1 (static half), AE-2 (static half), AE-10 (static half).
 *
 * The source scans fail closed: a missing source root or a vacuous scan is a test failure, never a skip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticAggregatesStaticTest {

    private val backupPackage = "com/yourname/expensetracker/data/backup"
    private val databasePackage = "com/yourname/expensetracker/data/database"

    // ── Source access (fail closed) ───────────────────────────────

    private val mainRoot: File by lazy {
        val userDir = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java")
        )
        candidates.firstOrNull { File(it, "$backupPackage/SemanticAggregates.kt").isFile }
            ?: error("Could not locate the production source root. user.dir=$userDir")
    }

    private fun source(relativePath: String): String {
        val file = File(mainRoot, relativePath)
        assertTrue("Missing production source $relativePath", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    /** Production code with comments removed, so documentation can mention forbidden words. */
    private fun codeOnly(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString("\n") { it.replace(Regex("//.*$"), "") }

    private fun normalised(text: String): String = text.replace(Regex("\\s+"), " ")

    // ── INV-1: closed registry, SQL-keyed API deleted ─────────────

    @Test
    fun INV1_registry_ids_and_section_version_are_pinned_together() {
        // Adding an aggregate id must bump SECTION_VERSION (INV-12): this test fails until both change.
        assertEquals(
            listOf(
                "EXPENSE_RAW_BY_TYPE_CURRENCY",
                "SPENDING_EFFECTIVE_BY_CURRENCY",
                "INCOME_EFFECTIVE_BY_CURRENCY",
                "RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY"
            ),
            SemanticAggregateId.values().map { it.name }
        )
        assertEquals(1, SemanticAggregates.SECTION_VERSION)
    }

    @Test
    fun INV1_registry_has_exactly_one_definition_per_id_in_declaration_order() {
        assertEquals(SemanticAggregateId.values().toList(), SemanticAggregates.registry.map { it.id })
        assertEquals(
            SemanticAggregateId.values().toList(),
            SemanticAggregateId.values().map { SemanticAggregates.definitionOf(it).id }
        )
    }

    @Test
    fun INV1_the_sql_keyed_api_no_longer_exists_in_production() {
        val deleted = listOf("SEMANTIC_AGGREGATE_QUERIES", "verifySemanticAggregates", "manifestSemanticAggregates")
        val files = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // Non-vacuity: the scan really covered the production tree and the verifier itself.
        assertTrue("scan saw only ${files.size} files", files.size > 100)
        assertTrue(files.any { it.name == "BackupVerifier.kt" })
        val offenders = files.flatMap { file ->
            val code = codeOnly(file.readText(Charsets.UTF_8))
            deleted.filter { code.contains(it) }.map { "${file.name}: $it" }
        }
        assertTrue("deleted SQL-keyed API still referenced: $offenders", offenders.isEmpty())
    }

    @Test
    fun INV1_registry_file_has_exactly_one_sql_source_and_one_query_call() {
        val code = codeOnly(source("$backupPackage/SemanticAggregates.kt"))

        // The only SQL text is the single querySql builder over code-owned definition fields.
        assertEquals(1, Regex("\"SELECT ").findAll(code).count())
        // The only query call receives the registry-built string, never a manifest-derived one.
        assertEquals(1, Regex("rawQuery\\(").findAll(code).count())
        assertTrue(normalised(code).contains("rawQuery(definition.querySql"))
    }

    @Test
    fun INV1_every_registry_query_is_built_only_from_registry_fields() {
        SemanticAggregates.registry.forEach { definition ->
            val expectedPrefix = "SELECT " + definition.keyColumns.joinToString(", ") { "CAST($it AS TEXT)" } + ", "
            assertTrue(definition.querySql, definition.querySql.startsWith(expectedPrefix))
            assertTrue(definition.querySql, definition.querySql.contains(" FROM ${definition.table}"))
            assertFalse("no bind-free manifest text: ${definition.querySql}", definition.querySql.contains(";"))
        }
    }

    // ── INV-2 / INV-3 / INV-4: canonical constants by symbol, nothing added ──

    @Test
    fun INV2_AE2_spending_and_income_use_the_canonical_constants_by_symbol() {
        val spending = SemanticAggregates.definitionOf(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
        val income = SemanticAggregates.definitionOf(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY)

        assertEquals(ExpenseDao.SPENDING_TYPE_SQL, spending.filterSql)
        assertEquals(SpendingInclusionSql.EFFECTIVE_AMOUNT, spending.valueSql)
        assertEquals(ExpenseDao.DEPOSIT_TYPE_SQL, income.filterSql)
        assertEquals(ExpenseDao.EFFECTIVE_AMOUNT_SQL, income.valueSql)
        assertEquals(listOf("currency"), spending.keyColumns)
        assertEquals(listOf("currency"), income.keyColumns)
        assertEquals("expenses", spending.table)
        assertEquals("expenses", income.table)
    }

    @Test
    fun INV2_AE2_raw_and_recurring_aggregates_are_unfiltered_and_keyed_by_stored_text() {
        val raw = SemanticAggregates.definitionOf(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        val recurring = SemanticAggregates.definitionOf(SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY)

        assertEquals("expenses", raw.table)
        assertEquals(listOf("transactionType", "currency"), raw.keyColumns)
        assertNull(raw.filterSql)
        assertEquals("amount", raw.valueSql)
        assertFalse(raw.querySql.contains(" WHERE "))
        assertEquals("manual_recurring_expenses", recurring.table)
        assertEquals(listOf("isActive", "currency"), recurring.keyColumns)
        assertNull(recurring.filterSql)
        assertEquals("amount", recurring.valueSql)
        assertFalse(recurring.querySql.contains(" WHERE "))
    }

    @Test
    fun INV2_AE2_no_type_literal_no_added_predicate_and_no_sql_sum_in_any_query() {
        SemanticAggregates.registry.forEach { definition ->
            val sql = definition.querySql
            // 'EXPENSE' and 'INCOME' are not TransactionType values (spec defect D1).
            assertFalse(sql, sql.contains("'EXPENSE'"))
            assertFalse(sql, sql.contains("'INCOME'"))
            // Exact sums are formed in Kotlin: SQL SUM(REAL) depends on the SQLite version (D6).
            assertFalse(sql, sql.contains("SUM(", ignoreCase = true))
            assertFalse(sql, sql.contains("COUNT(", ignoreCase = true))
            // Nothing is converted or read from the home-currency / base columns (INV-3).
            listOf("baseAmount", "baseCurrency", "exchangeRateUsed", "roundSnapshot").forEach {
                assertFalse("$sql must not read $it", sql.contains(it))
            }
        }
        // The spending filter is the bare type predicate: no isNotMine / exclusion predicate is added to it.
        val spendingFilter = SemanticAggregates.definitionOf(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY).filterSql
        assertFalse(spendingFilter!!.contains("isNotMine"))
        assertFalse(spendingFilter.contains("spendingExclusionReason"))
        val incomeFilter = SemanticAggregates.definitionOf(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).filterSql
        assertFalse(incomeFilter!!.contains("isNotMine"))
    }

    @Test
    fun INV2_AE2_registry_source_references_the_constants_by_symbol_and_copies_no_sql() {
        val code = normalised(codeOnly(source("$backupPackage/SemanticAggregates.kt")))

        assertTrue(code.contains("filterSql = SPENDING_TYPE_SQL"))
        assertTrue(code.contains("valueSql = SpendingInclusionSql.EFFECTIVE_AMOUNT"))
        assertTrue(code.contains("filterSql = DEPOSIT_TYPE_SQL"))
        assertTrue(code.contains("valueSql = EFFECTIVE_AMOUNT_SQL"))
        listOf("'EXPENSE'", "'INCOME'", "'PURCHASE'", "'DEPOSIT'", "isNotMine", "spendingExclusionReason", "SUM(", "COUNT(").forEach {
            assertFalse("SemanticAggregates.kt must not contain $it", code.contains(it))
        }
    }

    // ── INV-10: definition hashes ─────────────────────────────────

    @Test
    fun INV10_definition_hashes_are_16_lowercase_hex_characters_stable_and_distinct() {
        val hashes = SemanticAggregateId.values().map { SemanticAggregates.definitionHash(it) }

        hashes.forEach { assertTrue("hash '$it'", Regex("[0-9a-f]{16}").matches(it)) }
        assertEquals("every aggregate has its own definition hash", hashes.size, hashes.toSet().size)
        assertEquals(hashes, SemanticAggregateId.values().map { SemanticAggregates.definitionHash(it) })
        assertEquals(hashes, SemanticAggregates.registry.map { it.definitionHash })
    }

    // ── INV-9: fingerprinted tables and migration declarations ───

    @Test
    fun INV9_fingerprinted_tables_are_exactly_expenses_and_manual_recurring_expenses() {
        assertEquals(
            setOf("expenses", "manual_recurring_expenses"),
            SemanticAggregates.fingerprintedTables()
        )
        assertEquals(
            SemanticAggregates.registry.map { it.table }.toSet(),
            SemanticAggregates.fingerprintedTables()
        )
    }

    @Test
    fun INV9_declared_migration_invalidations_are_empty_and_the_minimum_source_schema_is_152() {
        assertTrue(SemanticAggregates.DECLARED_MIGRATION_INVALIDATIONS.isEmpty())
        assertEquals(152, SemanticAggregates.MIN_SOURCE_SCHEMA_VERSION)
    }

    @Test
    fun INV9_migrations_145_to_151_do_not_write_a_fingerprinted_table() {
        val migrations = migrationsSourceBefore152()
        // Non-vacuity: the six pre-152 migrations are all in the scanned text.
        listOf("MIGRATION_145_146", "MIGRATION_146_147", "MIGRATION_147_148", "MIGRATION_148_149", "MIGRATION_149_150", "MIGRATION_150_151")
            .forEach { assertTrue("scan must cover $it", migrations.contains(it)) }
        assertTrue("scan must see the migration statements", Regex("execSQL\\(").findAll(migrations).count() >= 10)

        assertEquals(emptyList<String>(), fingerprintedTableWrites(migrations))
    }

    @Test
    fun INV9_migration_151_to_152_only_adds_one_nullable_column_to_expenses() {
        val full = codeOnly(source("$databasePackage/DatabaseMigrations.kt"))
        val start = full.indexOf("val MIGRATION_151_152")
        // The region ends where the next migration (152 -> 153, refund netting) begins.
        val end = full.indexOf("val MIGRATION_152_153")
        assertTrue("MIGRATION_151_152 region not found", start in 0 until end)
        val region = full.substring(start, end)

        val writes = fingerprintedTableWrites(region)

        // The single write is the additive, default-NULL column: identity for every fingerprinted value.
        assertEquals(1, writes.size)
        assertTrue(writes.single(), writes.single().uppercase().startsWith("ALTER TABLE"))
        assertTrue(
            normalised(region).replace(Regex("\"\\s*\\+\\s*\""), "")
                .contains("ALTER TABLE `expenses` ADD COLUMN `spendingExclusionReason` TEXT DEFAULT NULL")
        )
    }

    @Test
    fun INV9_INV24_migration_152_to_153_only_adds_one_default_zero_column_to_expenses() {
        val full = codeOnly(source("$databasePackage/DatabaseMigrations.kt"))
        val start = full.indexOf("val MIGRATION_152_153")
        val end = full.indexOf("val ALL")
        assertTrue("MIGRATION_152_153 region not found", start in 0 until end)
        val region = full.substring(start, end)

        val writes = fingerprintedTableWrites(region)

        // The single fingerprinted-table write is the additive default-0.0 column; the new link table is
        // not a fingerprinted table and the FK references to expenses are not writes.
        assertEquals(writes.toString(), 1, writes.size)
        assertTrue(writes.single(), writes.single().uppercase().startsWith("ALTER TABLE"))
        assertTrue(
            normalised(region).replace(Regex("\"\\s*\\+\\s*\""), "")
                .contains("ALTER TABLE `expenses` ADD COLUMN `refundNettedAmount` REAL NOT NULL DEFAULT 0.0")
        )
    }

    @Test
    fun INV9_write_detector_flags_every_kind_of_fingerprinted_write_and_ignores_references() {
        val writes = listOf(
            "UPDATE expenses SET amount = 1",
            "update `expenses` set amount = 1",
            "INSERT INTO manual_recurring_expenses (id) VALUES (1)",
            "INSERT OR REPLACE INTO `expenses` (id) VALUES (1)",
            "DELETE FROM expenses WHERE id = 1",
            "REPLACE INTO expenses (id) VALUES (1)",
            "DROP TABLE IF EXISTS expenses",
            "ALTER TABLE `expenses` ADD COLUMN `x` TEXT",
            "ALTER TABLE expenses RENAME TO old_expenses",
            "ALTER TABLE expenses_new RENAME TO expenses",
            "CREATE TABLE expenses_tmp (id INTEGER)" + "; ALTER TABLE x RENAME TO manual_recurring_expenses",
            "\"INSERT INTO \" + \"expenses (id) VALUES (1)\""
        )
        writes.forEach { assertFalse("must be flagged: $it", fingerprintedTableWrites(it).isEmpty()) }

        val harmless = listOf(
            "FOREIGN KEY(`subscriptionId`) REFERENCES `manual_recurring_expenses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE",
            "CREATE TABLE IF NOT EXISTS `negotiation_outcomes` (`id` INTEGER)",
            "ALTER TABLE `installment_plans` ADD COLUMN `x` TEXT",
            "UPDATE background_job_runs SET errors = 0",
            "INSERT INTO savings_sweep_month_acceptance (monthStart) VALUES (1)",
            "SELECT COUNT(*) FROM expenses"
        )
        harmless.forEach { assertEquals("must not be flagged: $it", emptyList<String>(), fingerprintedTableWrites(it)) }
    }

    // ── INV-16: read-only, no open call ───────────────────────────

    @Test
    fun INV16_semantic_aggregates_file_opens_nothing_and_writes_nothing() {
        val code = codeOnly(source("$backupPackage/SemanticAggregates.kt"))

        listOf(
            "openDatabase", "openOrCreateDatabase", "OPEN_READWRITE", "execSQL", "beginTransaction",
            ".insert(", ".update(", ".delete(", "AppDatabase", "androidx.room"
        ).forEach { assertFalse("SemanticAggregates.kt must not contain $it", code.contains(it)) }
    }

    @Test
    fun INV16_backup_verifier_opens_only_read_only_handles_and_never_writes() {
        val code = codeOnly(source("$backupPackage/BackupVerifier.kt"))

        val opens = Regex("openDatabase\\(").findAll(code).count()
        assertTrue("BackupVerifier must open the database", opens > 0)
        assertEquals("every open must be OPEN_READONLY", opens, Regex("OPEN_READONLY").findAll(code).count())
        listOf(
            "openOrCreateDatabase", "OPEN_READWRITE", "OPEN_CREATE", "execSQL", "beginTransaction", "ATTACH ",
            "INSERT INTO", "UPDATE ", "DELETE FROM", "DROP ", "CREATE TABLE", "AppDatabase"
        ).forEach { assertFalse("BackupVerifier.kt must not contain $it", code.contains(it)) }
    }

    // ── Layering and bounded types ────────────────────────────────

    @Test
    fun domain_outcome_type_does_not_import_the_data_layer_and_has_no_failed_member() {
        val code = codeOnly(source("com/yourname/expensetracker/domain/backup/SemanticVerificationOutcome.kt"))

        assertFalse(code.contains("import com.yourname.expensetracker.data"))
        assertFalse(code.contains("SemanticCode"))
        assertFalse(code.contains("Failed"))
        assertEquals(
            listOf("LEGACY", "VERSION", "NOT_CAPTURED", "DEFINITION_CHANGED", "NO_MANIFEST"),
            SemanticNotVerifiableReason.values().map { it.name }
        )
    }

    @Test
    fun semantic_codes_and_checkpoints_are_pinned_and_not_diagnostic_reason_codes() {
        assertEquals(
            listOf(
                "SEMANTIC_VERIFIED", "SEMANTIC_MISMATCH", "SEMANTIC_QUERY_FAILED", "SEMANTIC_MANIFEST_MALFORMED",
                "SEMANTIC_CAPTURE_FAILED", "SEMANTIC_SELF_CHECK_FAILED", "SEMANTIC_BUCKET_LIMIT",
                "SEMANTIC_NOT_VERIFIABLE_LEGACY", "SEMANTIC_NOT_VERIFIABLE_VERSION",
                "SEMANTIC_NOT_VERIFIABLE_NOT_CAPTURED", "SEMANTIC_NOT_VERIFIABLE_DEFINITION_CHANGED",
                "SEMANTIC_NOT_VERIFIABLE_NO_MANIFEST"
            ),
            SemanticCode.values().map { it.name }
        )
        assertEquals(
            listOf("C0_BACKUP_SELF_CHECK", "C1_STAGED_SOURCE", "C2_STAGED_POST_MIGRATION", "C3_LIVE_POST_SWAP"),
            SemanticCheckpoint.values().map { it.name }
        )
        assertTrue(
            "SemanticCode must never be added to DiagnosticReasonCode (Q4)",
            DiagnosticReasonCode.values().none { it.name.startsWith("SEMANTIC") }
        )
    }

    @Test
    fun not_verifiable_reasons_map_one_to_one_onto_the_data_layer_codes() {
        SemanticNotVerifiableReason.values().forEach { reason ->
            assertEquals(
                SemanticCode.valueOf("SEMANTIC_NOT_VERIFIABLE_${reason.name}"),
                SemanticCode.forNotVerifiable(reason)
            )
        }
        SemanticNotCapturedReason.values().forEach { reason ->
            assertEquals(SemanticCode.valueOf(reason.name), reason.code)
        }
    }

    @Test
    fun INV14_value_types_render_bounded_text_without_keys_sums_or_hashes() {
        val bucket = AggregateBucket(listOf("PURCHASE", "EUR"), 7, 3, BigDecimal("1234.5"))
        val entry = AggregateEntry("deadbeefdeadbeef", listOf(bucket))
        val set = SemanticAggregateSet.ofCaptured(mapOf(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY to entry))

        listOf(bucket.toString(), entry.toString(), set.toString()).forEach { text ->
            listOf("PURCHASE", "EUR", "1234", "deadbeef").forEach {
                assertFalse("rendering must not contain $it: $text", text.contains(it))
            }
        }
    }

    // ── Detector ──────────────────────────────────────────────────

    private fun migrationsSourceBefore152(): String {
        val migrations = codeOnly(source("$databasePackage/DatabaseMigrations.kt"))
        val end = migrations.indexOf("val MIGRATION_151_152")
        assertTrue("MIGRATION_151_152 declaration not found", end > 0)
        val sweep = codeOnly(source("$databasePackage/SavingsSweepMonthMigration.kt"))
        return migrations.substring(0, end) + "\n" + sweep
    }

    /** Statements that INSERT / UPDATE / DELETE / REPLACE / DROP / ALTER / RENAME-to a fingerprinted table. */
    private fun fingerprintedTableWrites(code: String): List<String> {
        val joined = code.replace(Regex("\"\\s*\\+\\s*\""), "")
        val tables = SemanticAggregates.fingerprintedTables().joinToString("|") { Regex.escape(it) }
        val quote = "[`\"'\\[]?"
        val verbs = Regex(
            "\\b(INSERT\\s+(OR\\s+\\w+\\s+)?INTO|REPLACE\\s+INTO|UPDATE|DELETE\\s+FROM|DROP\\s+TABLE(\\s+IF\\s+EXISTS)?|" +
                "ALTER\\s+TABLE|CREATE\\s+TABLE(\\s+IF\\s+NOT\\s+EXISTS)?)\\s+$quote($tables)$quote(?![\\w])",
            RegexOption.IGNORE_CASE
        )
        val renames = Regex("RENAME\\s+TO\\s+$quote($tables)$quote(?![\\w])", RegexOption.IGNORE_CASE)
        return (verbs.findAll(joined) + renames.findAll(joined)).map { it.value }.toList()
    }
}
