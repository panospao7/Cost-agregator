#!/usr/bin/env kotlin

import java.io.File

// Forbidden patterns: direct DAO calls that bypass TransactionLifecycleCoordinator
val forbiddenPatterns = listOf(
    "expenseDao.updateCategory(" to "TransactionLifecycleCoordinator.updateCategory()",
    "expenseDao.updateCategoryNullable(" to "TransactionLifecycleCoordinator.updateCategory()",
    "expenseDao.updateMerchantAndKey(" to "TransactionLifecycleCoordinator.updateMerchant()",
    "expenseDao.updateTransactionType(" to "TransactionLifecycleCoordinator.updateType()",
    "expenseDao.updateTransferDirection(" to "TransactionLifecycleCoordinator.updateTransferDetails()",
    "expenseDao.updateTransferAccountName(" to "TransactionLifecycleCoordinator.updateTransferDetails()",
    "expenseDao.updateIsNotMine(" to "TransactionLifecycleCoordinator.updateOwnership()",
    "expenseDao.updateOwnerName(" to "TransactionLifecycleCoordinator.updateOwnership()",
    "expenseDao.updateIsSharedExpense(" to "TransactionLifecycleCoordinator.updateOwnership()",
    "expenseDao.updateSharedWithName(" to "TransactionLifecycleCoordinator.updateOwnership()",
    "expenseDao.updateMySharePercentage(" to "TransactionLifecycleCoordinator.updateOwnership()",
    "expenseDao.updateMyShareAmount(" to "TransactionLifecycleCoordinator.updateOwnership()",
    "expenseDao.updateLocation(" to "TransactionLifecycleCoordinator.updateLocation()",
    "expenseDao.clearLocation(" to "TransactionLifecycleCoordinator.updateLocation()",
)

// Files allowed to use these direct DAO calls
val allowlist = setOf(
    "TransactionLifecycleCoordinator.kt",
    "ReceiptLinkService.kt",        // RCP-30: circular dependency, documented
    "GroupTransactionCoordinator.kt", // atomic group operations, documented
    "GroupLifecycleCoordinator.kt",   // group lifecycle mutations, documented
    "GroupBalanceCalculator.kt",      // reads DAOs for net balance formula
    "LocationBackfillWorker.kt",     // backfill worker, documented
    "MerchantKeyBackfillWorker.kt"   // backfill worker, documented
)

val srcDir = File("app/src/main/java")
var violations = 0

srcDir.walkTopDown()
    .filter { it.isFile && it.extension == "kt" && !it.path.contains("test") && !it.path.contains("androidTest") }
    .forEach { file ->
        val fileName = file.name
        if (fileName in allowlist) return@forEach
        
        val content = file.readText()
        for ((pattern, replacement) in forbiddenPatterns) {
            if (pattern in content) {
                println("VIOLATION: ${file.path}: Direct call to '$pattern' — use $replacement instead")
                violations++
            }
        }
    }

if (violations > 0) {
    println("\nFound $violations lifecycle bypass violations. Fix them or add an allowlist entry.")
    System.exit(1)
} else {
    println("No lifecycle bypass violations found.")
}
