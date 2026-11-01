package com.yourname.expensetracker.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P-07-1 / S0b: every AppDatabase entity of the exported schema 152 must be classified in
 * the [BackupVerifier] table registry, with exactly one tier and no exclusion list. A table
 * added to the schema without a tier (or a tier entry for a table that no longer exists)
 * fails here.
 *
 * Synthetic: only the committed Room schema export and the verifier source are read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierTableCoverageTest {

    private val schemaVersion = 152

    @Test
    fun P071_registry_table_names_equal_exported_schema_152_entity_tables_exactly() {
        val schemaTables = schemaEntityTableNames()
        assertTrue("schema 152 must declare entity tables", schemaTables.isNotEmpty())

        val registry = BackupVerifier.allTableNames()

        assertEquals(
            "Untracked schema tables: ${(schemaTables - registry).sorted()}; " +
                "registry tables missing from schema: ${(registry - schemaTables).sorted()}",
            schemaTables,
            registry
        )
        assertEquals(schemaTables.size, registry.size)
    }

    @Test
    fun P071_every_schema_table_has_exactly_one_tier_and_the_tiers_partition_the_registry() {
        val schemaTables = schemaEntityTableNames()

        val byTier = BackupVerifier.VerificationTier.values().associateWith { tier ->
            schemaTables.filter { BackupVerifier.tableTier(it) == tier }.toSet()
        }

        // Disjoint and exhaustive: each table appears in exactly one tier bucket.
        assertEquals(schemaTables.size, byTier.values.sumOf { it.size })
        assertEquals(schemaTables, byTier.values.flatten().toSet())
        // The Tier 3 default for unknown names must not be what classifies a schema table.
        val unclassified = schemaTables - BackupVerifier.allTableNames()
        assertTrue("Schema tables without a registry entry: ${unclassified.sorted()}", unclassified.isEmpty())
        // Both strict tiers and the optional tier are populated.
        BackupVerifier.VerificationTier.values().forEach { tier ->
            assertTrue("$tier must classify at least one table", byTier.getValue(tier).isNotEmpty())
        }
    }

    @Test
    fun P071_tier_registry_literal_has_no_duplicate_table_keys() {
        // TABLE_TIERS is a mapOf literal: a duplicated key silently overwrites the earlier
        // tier, which a runtime map inspection cannot see. Scan the literal instead.
        val source = File(sourceRoot(), "com/yourname/expensetracker/data/backup/BackupVerifier.kt").readText()
        val start = source.indexOf("val TABLE_TIERS")
        assertTrue("TABLE_TIERS declaration must be present", start >= 0)
        val end = source.indexOf("// ── Verification result", start)
        assertTrue("end of TABLE_TIERS literal must be locatable", end > start)
        val entries = Regex("\"([a-z0-9_]+)\"\\s+to\\s+VerificationTier\\.(TIER_[A-Z0-9_]+)")
            .findAll(source.substring(start, end))
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "Duplicate TABLE_TIERS keys: ${entries.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()}",
            entries.size,
            entries.toSet().size
        )
        assertEquals(BackupVerifier.allTableNames(), entries.toSet())
    }

    @Test
    fun P071_s0b_tables_have_the_planned_tiers() {
        val tier1 = listOf(
            "source_stats_events",
            "warranty_lifecycle_events",
            "investment_transactions",
            "group_settlements",
            "group_lifecycle_events",
            "entity_source_links",
            "bank_statement_import_runs",
            "bank_statement_import_items",
            "negotiation_outcomes"
        )
        val tier3 = listOf(
            "pipeline_diagnostic_events",
            "operation_runs",
            "operation_run_events",
            "notification_intake"
        )
        tier1.forEach {
            assertTrue("$it must be tracked", it in BackupVerifier.allTableNames())
            assertEquals(it, BackupVerifier.VerificationTier.TIER_1_EXACT, BackupVerifier.tableTier(it))
        }
        tier3.forEach {
            assertTrue("$it must be tracked", it in BackupVerifier.allTableNames())
            assertEquals(it, BackupVerifier.VerificationTier.TIER_3_OPTIONAL, BackupVerifier.tableTier(it))
        }
        // Tier 3 tables are never required in a manifest.
        assertTrue(BackupVerifier.requiredManifestTables().intersect(tier3.toSet()).isEmpty())
    }

    private fun schemaEntityTableNames(): Set<String> {
        val file = File(schemaDirectory(), "$schemaVersion.json")
        assertTrue("Missing exported Room schema ${file.path}", file.isFile)
        val database = JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals("Loaded schema file must be version $schemaVersion", schemaVersion, database.getInt("version"))
        val entities = database.getJSONArray("entities")
        return (0 until entities.length())
            .map { entities.getJSONObject(it).getString("tableName") }
            .toSet()
    }

    private fun schemaDirectory(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        return listOf(
            File("app/schemas/com.yourname.expensetracker.data.database.AppDatabase"),
            File("schemas/com.yourname.expensetracker.data.database.AppDatabase"),
            File(userDir, "app/schemas/com.yourname.expensetracker.data.database.AppDatabase"),
            File(userDir, "schemas/com.yourname.expensetracker.data.database.AppDatabase")
        ).firstOrNull { it.isDirectory } ?: error("Could not locate Room schema directory. user.dir=$userDir")
    }

    private fun sourceRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        return listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java")
        ).firstOrNull { it.isDirectory } ?: error("Could not locate production source root. user.dir=$userDir")
    }
}
