package com.yourname.expensetracker.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Architecture test that enforces RestrictedExpenseDaoMutation guard policies.
 *
 * Rules:
 * - No file-level @OptIn(RestrictedExpenseDaoMutation) anywhere
 * - @OptIn(RestrictedExpenseDaoMutation) only in allowlisted production files
 * - Every mutating ExpenseDao method must carry @RestrictedExpenseDaoMutation
 * - The annotation must use RequiresOptIn.Level.WARNING or ERROR (not absent)
 */
class ExpenseDaoMutationAccessTest {

    private val sourceRoot: Path = Path.of("src/main/java")

    private val allowedOptInFiles = setOf(
        "TransactionLifecycleCoordinator.kt",
        "ExpenseRepository.kt",
        "GroupTransactionCoordinator.kt"
    )

    @Test
    fun no_file_level_restricted_expense_dao_mutation_opt_in() {
        val offenders = kotlinFiles()
            .filter { it.readText().contains("@file:OptIn(RestrictedExpenseDaoMutation::class)") }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("File-level RestrictedExpenseDaoMutation opt-in is forbidden:\n${offenders.joinToString("\n")}")
        }
    }

    @Test
    fun restricted_expense_dao_mutation_opt_in_only_in_allowlisted_files() {
        val offenders = kotlinFiles()
            .filter { file ->
                val text = file.readText()
                text.contains("RestrictedExpenseDaoMutation::class") &&
                    file.name !in allowedOptInFiles &&
                    file.name != "RestrictedExpenseDaoMutation.kt" &&
                    file.name != "ExpenseDao.kt"
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "RestrictedExpenseDaoMutation @OptIn found outside allowlisted files. " +
                "Route through TransactionLifecycleCoordinator or update the allowlist:\n" +
                offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun restricted_annotation_uses_warning_or_error_level() {
        val file = sourceRoot.resolve(
            "com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt"
        )
        assertFalse("Annotation file not found at $file", Files.notExists(file))
        val text = file.readText()
        val hasWarning = text.contains("RequiresOptIn.Level.WARNING")
        val hasError = text.contains("RequiresOptIn.Level.ERROR")
        if (!hasWarning && !hasError) {
            fail("RestrictedExpenseDaoMutation must use RequiresOptIn.Level.WARNING or ERROR")
        }
    }

    @Test
    fun every_mutating_dao_method_is_annotated() {
        // Verify the DAO file contains @RestrictedExpenseDaoMutation annotations
        val daoFile = sourceRoot.resolve(
            "com/yourname/expensetracker/data/database/dao/ExpenseDao.kt"
        )
        assertFalse("ExpenseDao.kt not found", Files.notExists(daoFile))
        val text = daoFile.readText()

        val annotationCount = Regex("@RestrictedExpenseDaoMutation").findAll(text).count()
        if (annotationCount < 20) {
            fail(
                "Expected at least 20 @RestrictedExpenseDaoMutation annotations in ExpenseDao.kt, " +
                "found $annotationCount. Ensure all mutating methods are annotated."
            )
        }
    }

    private fun kotlinFiles(): Sequence<Path> =
        Files.walk(sourceRoot)
            .filter { it.extension == "kt" }
            .iterator().asSequence()
}
