#!/usr/bin/env kotlin

/*
 * DAO Access Guardrail Check — Kotlin Script
 *
 * Scans the production source tree for files referencing "expenseDao."
 * and flags any file not in the approved list.
 *
 * Usage:
 *   kotlin -cp $CLASS_PATH dao-access-check.kts
 *   (or run as a Gradle task via Kotlin scripting plugin)
 *
 * Exit code:
 *   0 — All expenseDao access is in approved files
 *   1 — Violations found
 */

import java.io.File

// ── Configuration ──────────────────────────────────────────────────────────
val projectRoot = File(System.getProperty("user.dir") ?: ".")
val sourceDir = projectRoot.resolve("app/src/main/java")
val approvedFileList = projectRoot.resolve("scripts/guardrails/dao-approved-files.txt")

// ── Load approved file names ──────────────────────────────────────────────
val approvedNames: Set<String> = if (approvedFileList.exists()) {
    approvedFileList.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .toSet()
} else {
    System.err.println("WARNING: approved file list not found at ${approvedFileList.absolutePath}")
    emptySet()
}

// ── Scan for violations ───────────────────────────────────────────────────
val violations = mutableListOf<String>()

if (sourceDir.exists()) {
    sourceDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .forEach { file ->
            val content = file.readText()
            if (content.contains("expenseDao.")) {
                val relativePath = file.relativeTo(projectRoot).path
                val fileName = file.name
                if (fileName !in approvedNames) {
                    violations.add(relativePath)
                }
            }
        }
} else {
    System.err.println("ERROR: Source directory not found: ${sourceDir.absolutePath}")
    kotlin.system.exitProcess(1)
}

// ── Report ────────────────────────────────────────────────────────────────
if (violations.isEmpty()) {
    println("✅  DAO Access Guardrail: PASS — All expenseDao access is in approved files.")
    kotlin.system.exitProcess(0)
} else {
    println("❌  DAO Access Guardrail: FAIL — ${violations.size} violation(s) found:")
    println()
    violations.sorted().forEach { println("    - $it") }
    println()
    println("Review the violations above. Either:")
    println("  1. Add the file to the approved list (scripts/guardrails/dao-approved-files.txt)")
    println("     if it legitimately needs direct ExpenseDao access.")
    println("  2. Refactor the code to go through TransactionLifecycleCoordinator or")
    println("     an existing repository instead.")
    kotlin.system.exitProcess(1)
}
