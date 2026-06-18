/**
 * Lifecycle bypass guard — static code check.
 *
 * Fails if app/main source references ExpenseDao.insert/update/delete methods
 * outside the approved allowlist.
 *
 * Usage: groovy lifecycle-bypass-guard.groovy
 *
 * Allowed callers:
 * - TransactionLifecycleCoordinator
 * - Approved backfill workers
 * - Room migrations
 * - Debug-only repositories (guarded by BuildConfig.DEBUG)
 */

def violations = []
def srcDir = new File("../app/src/main/java")

def allowlist = [
    // Core lifecycle coordinator — sole owner of expense CUD
    "TransactionLifecycleCoordinator",

    // Approved backfill workers — limited column writes
    "LocationBackfillWorker",
    "MerchantKeyBackfillWorker",

    // Group cleanup — aggregate audit event added
    "GroupTransactionCoordinator",

    // Debug repositories — guarded by BuildConfig.DEBUG
    "DebugExpenseRepository",

    // Room migration SQL — direct DB version changes
    "AppDatabase",
]

def daoMutationPatterns = [
    ~/\bexpenseDao\.insert\b/,
    ~/\bexpenseDao\.update\b/,
    ~/\bexpenseDao\.delete\b/,
    ~/\bexpenseDao\.insertAll\b/,
    ~/\bexpenseDao\.deleteAll\b/,
]

srcDir.eachFileRecurse { file ->
    if (!file.name.endsWith(".kt")) return
    def text = file.text
    def className = file.name - ".kt"

    if (allowlist.any { className.contains(it) }) return

    daoMutationPatterns.each { pattern ->
        def matcher = text =~ pattern
        if (matcher.find()) {
            violations << "${file.path}: matches ${pattern.pattern()}"
        }
    }
}

if (violations) {
    println "LIFECYCLE BYPASS GUARD FAILED:"
    violations.each { println "  - $it" }
    println "\nAll expense CUD writes must route through TransactionLifecycleCoordinator."
    System.exit(1)
} else {
    println "Lifecycle bypass guard: PASSED — no violations found."
}
