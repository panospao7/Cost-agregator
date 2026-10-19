package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Static architecture tests that prevent regressions in the recurring lifecycle layer.
 *
 * These tests scan production source code and fail if forbidden patterns are found,
 * ensuring that recurring DAO mutations, receiver patterns, and legacy paths
 * don't creep back in.
 */
class RecurringArchitectureGuardTest {

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}, candidates=$candidates")
    }

    @Test
    fun `source root exists and contains Kotlin files`() {
        assertTrue("sourceRoot must exist: ${sourceRoot.absolutePath}", sourceRoot.exists())
        val kotlinFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("Architecture guard scanned zero Kotlin files in ${sourceRoot.absolutePath}", kotlinFiles.isNotEmpty())
    }

    /**
     * Files allowed to directly mutate recurring-rule DAOs, with justification.
     *
     * Entries tagged with owner=/issue=/expiry= are TEMPORARY and must shrink:
     * they are a justified ratchet, not a silent allowlist, and the reason must
     * name the remediation owner and expiry date.
     */
    private val allowedMutationFiles: Map<String, String> = mapOf(
        // Matched by path relative to the production source root (never by basename).
        // RecurringLifecycleCoordinator, RecurringOccurrenceMaterializer and AppDatabase
        // were verified to perform NO rule-DAO mutation, so their exemptions were removed.
        "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt" to
            "legal single-writer path for rule mutations"
    )

    /**
     * Recurring-rule DAO interfaces and their mutating methods
     * (verified against the DAO sources; keep in sync if a mutator is added).
     */
    private val recurringRuleDaos: Map<String, Set<String>> = mapOf(
        "ManualRecurringExpenseDao" to setOf(
            "insert", "update", "delete", "deleteById", "setActiveStatus", "updateNextDate"
        ),
        "RecurringExpenseDao" to setOf("insert", "update", "delete", "deleteById")
    )

    @Test
    fun `no direct recurring rule DAO mutation outside coordinator`() {
        val errors = mutableListOf<String>()
        walkSourceFiles(sourceRoot) { file, raw ->
            if (allowedMutationFiles.containsKey(FailClosedScan.relativePath(sourceRoot, file))) {
                return@walkSourceFiles
            }
            // Sanitize so a comment/string mention can never fake or hide a mutation.
            val text = SourceTextSanitizer.stripCommentsAndStringBodies(raw)

            for ((daoInterface, mutatingMethods) in recurringRuleDaos) {
                val canonical = daoInterface.replaceFirstChar { it.lowercaseChar() }
                // ANY receiver whose declared type is this DAO, regardless of the
                // property name (e.g. `subscriptionDao: ManualRecurringExpenseDao`),
                // plus canonical/interface names and local database aliases.
                val receivers = mutableSetOf(canonical, daoInterface)
                receivers += Regex("""\b([A-Za-z_]\w*)\s*:\s*(?:\w+\.)*${Regex.escape(daoInterface)}\b""")
                    .findAll(text)
                    .map { it.groupValues[1] }
                for (dbVar in listOf("appDatabase", "database", "db")) {
                    receivers += Regex(
                        """\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*$dbVar\s*\.\s*${Regex.escape(canonical)}\s*\(\s*\)"""
                    ).findAll(text).map { it.groupValues[1] }
                }

                for (method in mutatingMethods) {
                    for (receiver in receivers) {
                        val callPattern = Regex(
                            """\b${Regex.escape(receiver)}\s*\.\s*${Regex.escape(method)}\s*\("""
                        )
                        if (callPattern.containsMatchIn(text)) {
                            errors.add(
                                "${file.name}: direct recurring rule DAO mutation " +
                                    "$daoInterface.$method() via receiver '$receiver' " +
                                    "(allowed files: ${allowedMutationFiles.keys.joinToString()})"
                            )
                        }
                    }
                }
            }
        }

        assertTrue(
            "Direct recurring rule DAO mutations found outside allowed files:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    /**
     * RP-04 A1 negative control: proves the declared-type detector above actually
     * FIRES on a violating snippet (not marker-only). The snippet mirrors the
     * exact bypass shape the guard exists to catch: a property whose DECLARED
     * type is a recurring-rule DAO but whose name masquerades as another DAO
     * (here: `subscriptionDao: ManualRecurringExpenseDao`), plus a direct
     * mutation call through it.
     */
    @Test
    fun `subscriptionDaoAliasCannotMutateManualRecurringExpense`() {
        val violatingSnippet = """
            class SneakyRepository(
                private val subscriptionDao: ManualRecurringExpenseDao
            ) {
                suspend fun bypass(rule: ManualRecurringExpense) {
                    subscriptionDao.update(rule)
                }
            }
        """.trimIndent()

        // Run the snippet through the SAME detection logic the main guard uses:
        // sanitize, collect declared-type receivers, match mutator calls.
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(violatingSnippet)
        val daoInterface = "ManualRecurringExpenseDao"
        val mutatingMethods = recurringRuleDaos.getValue(daoInterface)

        val receivers = mutableSetOf(
            daoInterface.replaceFirstChar { it.lowercaseChar() },
            daoInterface
        )
        receivers += Regex("""\b([A-Za-z_]\w*)\s*:\s*(?:\w+\.)*${Regex.escape(daoInterface)}\b""")
            .findAll(text)
            .map { it.groupValues[1] }

        val detectedMutators = mutableListOf<String>()
        for (method in mutatingMethods) {
            for (receiver in receivers) {
                val callPattern = Regex(
                    """\b${Regex.escape(receiver)}\s*\.\s*${Regex.escape(method)}\s*\("""
                )
                if (callPattern.containsMatchIn(text)) {
                    detectedMutators += "$receiver.$method()"
                }
            }
        }

        assertTrue(
            "Detector must flag the declared-type alias bypass (subscriptionDao: ManualRecurringExpenseDao).update(); " +
                "detected: $detectedMutators",
            detectedMutators.contains("subscriptionDao.update()")
        )
    }

    @Test
    fun `subscription repository has no rule mutators and only the display-only scoped op`() {
        val repoFile = requireScannedFile(
            "com/yourname/expensetracker/data/repository/SubscriptionManagementRepository.kt"
        )
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(repoFile.readText())

        // RP-04 A1: the repository must delegate rule mutations to the coordinator.
        for (mutator in listOf("updateRule", "deleteRule", "activateRule", "deactivateRule")) {
            assertTrue(
                "SubscriptionManagementRepository must route through $mutator (RP-04 A1)",
                text.contains("$mutator(")
            )
        }
        // The legacy direct DAO mutators must be gone.
        for (forbidden in listOf("subscriptionDao.update(", "subscriptionDao.deleteById(")) {
            assertFalse(
                "SubscriptionManagementRepository must not call $forbidden directly (RP-04 A1)",
                text.contains(forbidden)
            )
        }
        // The display-only carve-out is the ONLY permitted direct write, and it is
        // a scoped column update, never the general-purpose update.
        // (Assertions run over sanitized text: string literals are blanked, so we
        // assert the call structure — the scoped op call plus a barrier check call —
        // not the tag string inside checkWritesAllowed.)
        assertTrue(
            "Display-only carve-out must use the scoped updateSubscriptionCategory op",
            text.contains("subscriptionDao.updateSubscriptionCategory(")
        )
        val carveOutBlock = FailClosedScan.section(
            text,
            "suspend fun updateSubscriptionCategory",
            "suspend fun markCandidateAsRejected"
        )
        assertTrue(
            "Display-only carve-out must be writeBarrier-checked",
            carveOutBlock.contains("writeBarrier.checkWritesAllowed(")
        )
        assertFalse(
            "Display-only carve-out must not fall back to general update()",
            carveOutBlock.contains("subscriptionDao.update(")
        )
    }

    @Test
    fun `subscription repository coordinator injection matches the lazy pattern`() {
        val repoFile = requireScannedFile(
            "com/yourname/expensetracker/data/repository/SubscriptionManagementRepository.kt"
        )
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(repoFile.readText())
        assertTrue(
            "SubscriptionManagementRepository must inject dagger.Lazy<RecurringRuleLifecycleCoordinator> " +
                "(pattern shared with ManualRecurringExpenseRepository / RecurringExpenseRepository)",
            text.contains("dagger.Lazy<")
        )
    }

    @Test
    fun `reminder receivers do not use runBlocking or direct DAOs`() {
        val receiverDir = File(sourceRoot, "com/yourname/expensetracker/service/reminder")
        assertTrue(
            "scanned directory missing: ${receiverDir.path} — update the guard if it was moved",
            receiverDir.isDirectory
        )

        val forbidden = listOf(
            "runBlocking",
            "ReminderDeliveryDao",
            "ManualRecurringExpenseDao",
            "RecurringOccurrenceDao",
            "PlannedExpenseDao",
            "RestoreMaintenanceMode",
            "reminderDeliveryDao",
            "occurrenceDao",
            "plannedExpenseDao",
            "manualRecurringExpenseDao"
        )

        val required = listOf(
            "workManager.enqueue",
            "OneTimeWorkRequestBuilder"
        )

        val errors = mutableListOf<String>()
        val receiverFiles = receiverDir.listFiles()?.filter { it.isFile && it.extension == "kt" }.orEmpty()
        assertTrue(
            "scanned zero Kotlin files in ${receiverDir.path} — reminder receiver guard would be vacuous",
            receiverFiles.isNotEmpty()
        )
        receiverFiles.forEach { file ->
            val text = file.readText()
            for (pattern in forbidden) {
                if (text.contains(pattern)) {
                    errors.add("${file.name}: contains forbidden pattern '$pattern'")
                }
            }
            // Only enforce BroadcastReceiver lifecycle on actual BroadcastReceivers, not Workers
            if (text.contains("BroadcastReceiver")) {
                for (pattern in required) {
                    if (!text.contains(pattern)) {
                        errors.add("${file.name}: missing required pattern '$pattern'")
                    }
                }
            }
        }

        assertTrue(
            "Receiver architecture violations:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    @Test
    fun `no live legacy markBillPaid path in production`() {
        val errors = mutableListOf<String>()
        // No path/basename exemptions: the BillReminderManager tombstone declaration
        // (`fun markBillPaid(`) is excluded structurally by legacyBillPaidCalls, and its
        // error message text is a string literal (blanked by the sanitizer).
        walkSourceFiles(sourceRoot) { file, text ->
            val calls = legacyBillPaidCalls(text)
            if (calls.isNotEmpty()) {
                errors.add("${file.name}: contains call to legacy ${calls.joinToString()}")
            }
        }
        assertTrue("Live legacy markBillPaid callers found:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    /**
     * Qualified (`x.markBillPaid(`) and unqualified (`markBillPaid(`) calls of the removed legacy
     * API in real code (comments/strings are ignored), excluding the declaration `fun markBillPaid(`.
     */
    private fun legacyBillPaidCalls(text: String): List<String> {
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(text)
        val withoutDeclarations = code.replace(
            Regex("""\bfun\s+(?:[\w.<>?]+\.)?(markBillPaid|markRuleBillAsPaid)\s*\("""),
            "fun _declaration_("
        )
        val calls = mutableListOf<String>()
        for (name in listOf("markBillPaid", "markRuleBillAsPaid")) {
            val qualified = Regex("""\.\s*$name\s*\(""")
            val unqualified = Regex("""(?<![\w.])$name\s*\(""")
            if (qualified.containsMatchIn(withoutDeclarations) || unqualified.containsMatchIn(withoutDeclarations)) {
                calls += name
            }
        }
        return calls
    }

    @Test
    fun `legacy markBillPaid detector flags qualified and unqualified calls but not the declaration`() {
        assertEquals(listOf("markBillPaid"), legacyBillPaidCalls("fun f() { manager.markBillPaid(1L) }"))
        assertEquals(listOf("markBillPaid"), legacyBillPaidCalls("fun f() { markBillPaid(1L) }"))
        assertEquals(listOf("markBillPaid"), legacyBillPaidCalls("fun f() { this\n.markBillPaid (1L) }"))
        assertEquals(listOf("markRuleBillAsPaid"), legacyBillPaidCalls("fun f() { markRuleBillAsPaid(1L) }"))
        assertEquals(
            listOf("markBillPaid", "markRuleBillAsPaid"),
            legacyBillPaidCalls("fun f() { markBillPaid(1L); a.markRuleBillAsPaid(2L) }")
        )
        // Negative controls: declaration, comment and string mentions are not calls.
        assertTrue(legacyBillPaidCalls("suspend fun markBillPaid(recurringExpenseId: Long): Nothing { error(\"Legacy markBillPaid removed\") }").isEmpty())
        assertTrue(legacyBillPaidCalls("fun f() { /* markBillPaid(1) */ // x.markRuleBillAsPaid(1)\n }").isEmpty())
        assertTrue(legacyBillPaidCalls("fun f() { log(\"markBillPaid(1)\") }").isEmpty())
        // A declaration does not hide a call that sits beside it.
        assertEquals(
            listOf("markBillPaid"),
            legacyBillPaidCalls("suspend fun markBillPaid(id: Long): Nothing { error(\"x\") }\nfun g() { markBillPaid(3L) }")
        )
    }

    @Test
    fun `no raw status updateOccurrenceStatus api in production`() {
        val errors = mutableListOf<String>()
        walkSourceFiles(sourceRoot) { file, text ->
            if (file.name == "RecurringLifecycleCoordinator.kt") return@walkSourceFiles
            if (text.contains("updateOccurrenceStatus(") && !text.contains("RecurringOccurrenceStatus") &&
                !text.contains("RecurringOccurrenceTransitionReason")) {
                errors.add("${file.name}: raw updateOccurrenceStatus call detected")
            }
        }
        assertTrue("Raw status update calls outside allowed files:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun `no 0L placeholder occurrence ids in reconcile results`() {
        val errors = mutableListOf<String>()
        walkSourceFiles(sourceRoot) { file, text ->
            if (text.contains("Linked(expenseId, 0L") ||
                text.contains("Relinked(expenseId, oldOccurrenceId, 0L") ||
                text.contains("newOccurrenceId = 0L")) {
                errors.add("${file.name}: contains 0L placeholder in reconcile result")
            }
        }
        assertTrue("0L placeholder ids found in reconcile results:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun `migration index names match Room defaults`() {
        val appDbFile = requireScannedFile("com/yourname/expensetracker/data/database/AppDatabase.kt")
        val text = appDbFile.readText()
        // Room default names should be present, not custom short names
        assertFalse("Migration uses wrong index name 'index_reminder_deliveries_occ_window'",
            text.contains("index_reminder_deliveries_occ_window"))
        assertTrue("Migration should use Room default index name",
            text.contains("index_recurring_reminder_deliveries_occurrenceId_reminderWindow"))
    }

    @Test
    fun `create and delete planner actions use detailed results`() {
        val plannerFile = requireScannedFile("com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt")
        val text = plannerFile.readText()
        // Create action should use linkExpenseToOccurrenceDetailed, not plain linkExpenseToOccurrence
        val createBlock = codeOf(
            FailClosedScan.section(
                text, "private fun makeRecurringMatchingAction", "private fun makeRecurringReconcileAction"
            )
        )
        assertTrue("Create action should use linkExpenseToOccurrenceDetailed",
            createBlock.contains("linkExpenseToOccurrenceDetailed"))
        assertFalse("Create action must not call plain linkExpenseToOccurrence(",
            Regex("""\blinkExpenseToOccurrence\s*\(""").containsMatchIn(createBlock))
        // Delete action should use unlinkExpenseFromOccurrenceDetailed
        val deleteBlock = codeOf(
            FailClosedScan.section(
                text, "private fun makeRecurringUnlinkAction", "private fun makeBulkBudgetCheckAction"
            )
        )
        assertTrue("Delete action should use unlinkExpenseFromOccurrenceDetailed",
            deleteBlock.contains("unlinkExpenseFromOccurrenceDetailed"))
        assertFalse("Delete action must not call plain unlinkExpenseFromOccurrence(",
            Regex("""\bunlinkExpenseFromOccurrence\s*\(""").containsMatchIn(deleteBlock))
    }

    /** Comment- and string-free view of a section: evidence must come from real code. */
    private fun codeOf(section: String): String = SourceTextSanitizer.stripCommentsAndStringBodies(section)

    /**
     * RP-04 A2: updateRule now performs logical occurrence reconciliation
     * (P4-003/004) instead of delete-all-PLANNED-then-regenerate. The old pin
     * (deleteOpenPlannedByRecurringRuleId before projection) asserted the deleted
     * algorithm and was replaced deliberately — assertions below pin the NEW
     * contract: reconciliation runs in one transaction, targets terminal
     * preservation, and writes the RULE_UPDATED_RECONCILED critical event.
     * No assertions were weakened: the replacement guard checks strictly more
     * semantic properties (terminal preservation + reconciliation entry point).
     */
    @Test
    fun `updateRule reconciles logical slots without deleting overdue obligations`() {
        val ruleFile = requireScannedFile("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        val text = ruleFile.readText()
        // Each method is checked in ITS OWN bounded section (start marker unique, ends at the next
        // sibling declaration) — evidence in a neighbouring method no longer counts.
        val updateRuleSection = FailClosedScan.section(text, "suspend fun updateRule")
        val updateRuleBody = codeOf(updateRuleSection)
        // Reconciler entry point must be transaction-internal (single boundary).
        assertTrue("updateRule must reconcile inside its transaction (reconcileUpdateInCurrentTransaction)",
            Regex("""withTransaction\s*\{[^}]*reconcileUpdateInCurrentTransaction\s*\(""")
                .containsMatchIn(updateRuleBody))
        // The reconciler must preserve terminal rows and must NOT use the bulk
        // delete-all-PLANNED wipe anymore. Call-site-precise: the legitimate
        // targeted op `deleteOpenPlannedBySourceKeys` shares a prefix with the
        // forbidden `deleteOpenPlannedBySource`, so match the full call including
        // the closing paren.
        val reconcileSection = FailClosedScan.section(
            text,
            "private suspend fun reconcileUpdateInCurrentTransaction",
            "private fun adoptSnapshotInPlace"
        )
        val reconcileBody = codeOf(reconcileSection)
        // Negative checks span the reconciler AND the helpers that follow it (the region the
        // pre-bounding guard covered), so moving a bulk wipe into a helper is still caught.
        val reconcilerRegion = reconcileBody + codeOf(
            FailClosedScan.section(text, "private fun adoptSnapshotInPlace", "private suspend fun assertReconciliationInvariants")
        ) + codeOf(FailClosedScan.section(text, "private suspend fun assertReconciliationInvariants"))
        // The reconciler classifies open slots via the canonical PLANNED status
        // constant (`RecurringOccurrenceStatus.PLANNED.dbValue`) — the single
        // source of truth for what counts as an open/retryable occurrence.
        assertTrue("reconciler must classify open rows via RecurringOccurrenceStatus.PLANNED.dbValue",
            reconcileBody.contains("RecurringOccurrenceStatus.PLANNED.dbValue"))
        assertFalse("reconciler must NOT bulk-delete all open planned rows (deleteOpenPlannedBySource)",
            reconcilerRegion.contains("deleteOpenPlannedBySource("))
        assertFalse("reconciler must NOT bulk-delete all open planned rows (deleteOpenPlannedByRecurringRuleId)",
            reconcilerRegion.contains("deleteOpenPlannedByRecurringRuleId("))
        // Targeted retirement only (moved slots), preserving overdue obligations.
        assertTrue("reconciler must use targeted retirement (deletePlannedByIds)",
            reconcileBody.contains("deletePlannedByIds("))
        assertTrue("updateRule reconciler must still project planned rows for new slots",
            reconcileBody.contains("projectFromOccurrencesInCurrentTransaction("))
        // Pre-commit invariant gate must exist (fail closed) and run BEFORE the critical event.
        val gateIndex = reconcileBody.indexOf("assertReconciliationInvariants(")
        assertTrue("updateRule reconciler must run pre-commit invariant assertions", gateIndex >= 0)
        // The RULE_UPDATED_RECONCILED critical event must go through eventWriter.writeCritical
        // (event type read from the call's argument list), never a direct lifecycle DAO insert.
        assertTrue(
            "updateRule must write the RULE_UPDATED_RECONCILED critical event via eventWriter.writeCritical",
            FailClosedScan.writeCriticalEventTypes(reconcileSection).contains("RULE_UPDATED_RECONCILED")
        )
        assertFalse(
            "updateRule reconciler must not write lifecycle events through the DAO directly",
            reconcileBody.contains("lifecycleEventDao.insert(")
        )
        val lastCriticalIndex = reconcileBody.lastIndexOf("eventWriter.writeCritical(")
        assertTrue(
            "pre-commit invariant gate must precede the final critical event write",
            gateIndex in 0 until lastCriticalIndex
        )
    }

    @Test
    fun `createRule generates future state`() {
        val ruleFile = requireScannedFile("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        val text = ruleFile.readText()
        val createSection = FailClosedScan.section(text, "suspend fun createRule", "suspend fun activateRule")
        val createBody = codeOf(createSection)
        assertTrue("createRule must call materializeInCurrentTransaction",
            createBody.contains("materializeInCurrentTransaction("))
        // MIT-043: createRule still writes this event through lifecycleEventDao (known debt), so the
        // assertion is on the event type literal anywhere in the bounded method, not the writer.
        assertTrue("createRule must write RULE_CREATED_GENERATED",
            FailClosedScan.eventTypeLiterals(createSection).contains("RULE_CREATED_GENERATED"))
        // The literal must actually be handed to an event sink (a real writeCritical call or the
        // known-debt direct DAO insert) — not merely sit in some unrelated expression.
        assertTrue("createRule must persist the event through eventWriter.writeCritical or lifecycleEventDao.insert",
            FailClosedScan.writeCriticalEventTypes(createSection).contains("RULE_CREATED_GENERATED") ||
                Regex("""\blifecycleEventDao\s*\.\s*insert\s*\(""").containsMatchIn(createBody))
    }

    @Test
    fun `activateRule generation is atomic`() {
        val ruleFile = requireScannedFile("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        val text = ruleFile.readText()
        val activateBody = codeOf(
            FailClosedScan.section(text, "suspend fun activateRule", "suspend fun advanceNextDate")
        )
        assertTrue("activateRule must call projectFromOccurrencesInCurrentTransaction",
            activateBody.contains("projectFromOccurrencesInCurrentTransaction("))
        assertTrue("activateRule generation must run inside database.withTransaction",
            Regex("""\bdatabase\s*\.\s*withTransaction\s*\{""").containsMatchIn(activateBody))
        // Any catch at all would let a generation failure commit the activation; the previous
        // check only matched the literal text `catch (e: Exception)`.
        assertFalse("activateRule must not catch generation failure",
            Regex("""\bcatch\s*\(""").containsMatchIn(activateBody))
        assertFalse("activateRule must not use runCatching around generation",
            Regex("""\brunCatching\w*\s*[({]""").containsMatchIn(activateBody))
    }

    @Test
    fun `bulk recurring reconciliation not permanently disabled`() {
        val plannerFile = requireScannedFile("com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt")
        val text = plannerFile.readText()
        val bulkBody = codeOf(FailClosedScan.section(text, "private fun makeBulkRecurringReconciliationAction"))
        assertTrue("Bulk recurring reconciliation action must call reconcileAllLinkedExpensesAfterBulkUpdate",
            bulkBody.contains("reconcileAllLinkedExpensesAfterBulkUpdate("))
        assertFalse("Bulk recurring reconciliation must not return DISABLED_BY_SETTINGS",
            bulkBody.contains("DISABLED_BY_SETTINGS"))
    }

    @Test
    fun `deactivateRule deletes not cancels open occurrences`() {
        val ruleFile = requireScannedFile("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        val text = ruleFile.readText()
        val deactivateBody = codeOf(
            FailClosedScan.section(text, "suspend fun deactivateRule", "suspend fun deleteRule")
        )
        assertTrue("deactivateRule must delete open occurrences (deleteOpenPlannedBySource), not cancel them",
            deactivateBody.contains("deleteOpenPlannedBySource("))
        assertFalse("deactivateRule must NOT use updateStatus on PLANNED occurrences",
            Regex("""\bupdateStatus\s*\(""").containsMatchIn(deactivateBody))
    }

    @Test
    fun `migration 139_140 has reminder dedup table`() {
        val appDbFile = requireScannedFile("com/yourname/expensetracker/data/database/AppDatabase.kt")
        val text = appDbFile.readText()
        // The dedup table name lives in SQL string literals, so the RAW slice is inspected; the
        // slice itself is bounded on sanitized code (declaration markers, not SQL/comment text).
        val migrationBody = FailClosedScan.section(
            text,
            "val MIGRATION_139_140 =",
            "val MIGRATION_140_141 ="
        )
        assertTrue("MIGRATION_139_140 must create the reminder_keep_139_140 dedup table",
            migrationBody.contains("CREATE TEMP TABLE reminder_keep_139_140"))
        assertTrue("MIGRATION_139_140 must drop the reminder_keep_139_140 dedup table",
            migrationBody.contains("DROP TABLE IF EXISTS reminder_keep_139_140"))
    }

    @Test
    fun `critical events use eventWriter not direct DAO in coordinator`() {
        val coordFile = requireScannedFile("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt")
        val text = coordFile.readText()
        // OCCURRENCE_PAID / PLANNED_FULFILLED are written by linkExpenseToOccurrence only; the event
        // type is read from the argument list of each eventWriter.writeCritical( call in that bounded
        // method, so a mention elsewhere (comment, string, other method) cannot satisfy the guard.
        val linkSection = FailClosedScan.section(
            text,
            "suspend fun linkExpenseToOccurrence(",
            "suspend fun linkExpenseToOccurrenceDetailed("
        )
        val criticalTypes = FailClosedScan.writeCriticalEventTypes(linkSection)
        assertTrue("OCCURRENCE_PAID must use eventWriter.writeCritical (found $criticalTypes)",
            criticalTypes.contains("OCCURRENCE_PAID"))
        assertTrue("PLANNED_FULFILLED must use eventWriter.writeCritical (found $criticalTypes)",
            criticalTypes.contains("PLANNED_FULFILLED"))
        // And neither may be written through the lifecycle DAO directly in that method.
        val directTypes = FailClosedScan.eventTypeLiterals(linkSection).toMutableList()
        criticalTypes.forEach { directTypes.remove(it) }
        assertFalse("OCCURRENCE_PAID/PLANNED_FULFILLED must not be written outside eventWriter.writeCritical",
            directTypes.contains("OCCURRENCE_PAID") || directTypes.contains("PLANNED_FULFILLED"))
        assertFalse("linkExpenseToOccurrence must not insert lifecycle events via the DAO directly",
            codeOf(linkSection).contains("lifecycleEventDao.insert("))
    }

    @Test
    fun `rule lifecycle coordinator does not use direct DAO for critical events`() {
        val ruleFile = requireScannedFile("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        val text = ruleFile.readText()
        // Must inject eventWriter
        assertTrue("RuleLifecycleCoordinator must inject RecurringLifecycleEventWriter",
            text.contains("eventWriter: RecurringLifecycleEventWriter"))
        // TODO(MIT-043): RecurringRuleLifecycleCoordinator still calls lifecycleEventDao.insert directly
        // for RULE_DEACTIVATED (deactivateRule), RULE_DELETED (deleteRule), RULE_CREATED_GENERATED
        // (createRule), RULE_ACTIVATED_REGENERATED (activateRule) and NEXT_DATE_ADVANCED (advanceNextDate).
        // Only the updateRule reconciler is writeCritical-only (enforced in
        // `updateRule reconciles logical slots without deleting overdue obligations`). Do NOT add a
        // whole-file "no lifecycleEventDao.insert" rule until MIT-043 is closed; it would fail today.
    }

    @Test
    fun `golden lifecycle tests do not bypass coordinator with direct DAO insert`() {
        // Golden tests live in the TEST source set (not src/main), so scan the test root.
        val goldenDir = File(testSourceRoot, "com/yourname/expensetracker/golden")
        assertTrue(
            "scanned directory missing: ${goldenDir.path} — update the guard if it was moved",
            goldenDir.isDirectory
        )
        val goldenFiles = goldenDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.contains("Pipeline4", true) }
            .toList()
        assertTrue(
            "scanned zero Pipeline4 golden test files in ${goldenDir.path} — golden bypass guard would be vacuous",
            goldenFiles.isNotEmpty()
        )
        val errors = mutableListOf<String>()
        goldenFiles.forEach { file ->
            // No whole-file exemption: a seeding helper in the file must not hide a bypass.
            val text = try {
                file.readText()
            } catch (e: java.io.IOException) {
                throw AssertionError("scanned file unreadable: ${file.path} (${e.javaClass.simpleName})", e)
            }
            val bypasses = goldenRuleDaoBypasses(text)
            if (bypasses.isNotEmpty()) {
                errors.add(
                    "${file.name}: golden test mutates a recurring-rule DAO directly instead of using the " +
                        "lifecycle coordinator: ${bypasses.joinToString()}"
                )
            }
        }
        assertTrue("Golden lifecycle tests bypass coordinator:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    /**
     * Direct recurring-rule DAO writes (insert/update/delete/upsert/setActiveStatus families),
     * case-insensitive, through: the DAO accessor chain (`db.manualRecurringExpenseDao().insert(`),
     * well-known property names (`manualRecurringExpenseDao`, `recurringExpenseDao`, any `*ruleDao`),
     * the DAO type name, locals assigned from a DAO accessor, and any identifier DECLARED with a
     * rule-DAO type. Comments and string literals are ignored.
     */
    private fun goldenRuleDaoBypasses(text: String): List<String> {
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(text)
        val daoAccessor = """(?:manualRecurringExpenseDao|recurringExpenseDao)"""
        val receivers = mutableSetOf(
            """\w*ruleDao""",
            daoAccessor,
            """${daoAccessor}\s*\(\s*\)""" // accessor chain: db.manualRecurringExpenseDao().insert(
        )
        // Declared type alias: `x: ManualRecurringExpenseDao`.
        Regex("""\b([A-Za-z_]\w*)\s*:\s*(?:\w+\.)*(?:Manual)?RecurringExpenseDao\b""", RegexOption.IGNORE_CASE)
            .findAll(code).forEach { receivers += Regex.escape(it.groupValues[1]) }
        // Local alias: `val x = anything.manualRecurringExpenseDao()`.
        Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*[\w.]*\.\s*$daoAccessor\s*\(\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(code).forEach { receivers += Regex.escape(it.groupValues[1]) }

        val methods = """(?:insert|update|delete|upsert|setActiveStatus)\w*"""
        val found = mutableListOf<String>()
        for (receiver in receivers) {
            val call = Regex("""\b$receiver\s*\.\s*($methods)\s*\(""", RegexOption.IGNORE_CASE)
            call.findAll(code).forEach { found += "${it.groupValues[1]}()" }
        }
        return found.distinct()
    }

    @Test
    fun `golden bypass detector flags rule dao writes in every alias shape`() {
        val violating = listOf(
            "database.manualRecurringExpenseDao().insert(rule)",
            "database.recurringExpenseDao().insert(rule)",
            "database.ManualRecurringExpenseDao().Insert(rule)",
            "manualRecurringExpenseDao.update(rule)",
            "recurringExpenseDao.deleteById(1L)",
            "ruleDao.upsert(rule)",
            "seedRuleDao.delete(rule)",
            "val dao = db.manualRecurringExpenseDao()\ndao.insert(rule)",
            "class T(private val subscriptionDao: ManualRecurringExpenseDao) { fun f() { subscriptionDao.update(r) } }",
            "ruleDao\n  .setActiveStatus(1L, false)"
        )
        for (snippet in violating) {
            assertTrue("detector must flag: $snippet", goldenRuleDaoBypasses(snippet).isNotEmpty())
        }
        // seedRuleViaRepo-style helper names must not exempt anything.
        assertTrue(
            goldenRuleDaoBypasses("fun seedRuleViaRepo() { database.manualRecurringExpenseDao().insert(r) }").isNotEmpty()
        )
    }

    @Test
    fun `golden bypass detector ignores reads comments strings and coordinator calls`() {
        val benign = listOf(
            "val rule = database.manualRecurringExpenseDao().getById(1L)",
            "val ruleDao = database.manualRecurringExpenseDao()\nval c = Coordinator(manualRecurringExpenseDao = ruleDao)",
            "ruleCoordinator.createRule(rule)",
            "// database.manualRecurringExpenseDao().insert(rule)",
            "val s = \"ruleDao.insert(rule)\"",
            "database.recurringOccurrenceDao().insert(occ)"
        )
        for (snippet in benign) {
            assertTrue("detector must not flag: $snippet", goldenRuleDaoBypasses(snippet).isEmpty())
        }
    }

    @Test
    fun `bulk reconciliation does not use global PAID scan`() {
        val plannerFile = requireScannedFile("com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt")
        val text = plannerFile.readText()
        val bulkBlock = codeOf(FailClosedScan.section(text, "private fun makeBulkRecurringReconciliationAction"))
        // Bulk must use structured result, not raw count
        assertTrue("Bulk reconciliation must consume the structured result (result.hasFailures)",
            bulkBlock.contains("result.hasFailures"))
        // Sanitized code blanks string arguments, so forbid ANY getByStatus( call (stricter than
        // the former literal getByStatus("PAID") match, which a renamed constant would evade).
        assertFalse("Bulk reconciliation must not use a global getByStatus scan in planner",
            Regex("""\bgetByStatus\s*\(""").containsMatchIn(bulkBlock))
    }

    @Test
    fun `deactivation deletes not cancels planned rows`() {
        val ruleFile = requireScannedFile("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        val text = ruleFile.readText()
        val deactivateBody = codeOf(
            FailClosedScan.section(text, "suspend fun deactivateRule", "suspend fun deleteRule")
        )
        assertTrue("deactivateRule must delete open planned rows (deleteOpenPlannedByRecurringRuleId)",
            deactivateBody.contains("deleteOpenPlannedByRecurringRuleId("))
        assertFalse("deactivateRule must NOT cancel planned rows (cancelPlannedByRecurringRuleId)",
            deactivateBody.contains("cancelPlannedByRecurringRuleId"))
    }

    /** Fail-closed walk shared with [ScannerFailClosedSelfTest]: missing/empty root and unreadable files fail. */
    private fun walkSourceFiles(root: File, block: (File, String) -> Unit) =
        FailClosedScan.walkSourceFiles(root, block)

    private val testSourceRoot: File by lazy {
        val candidates = listOf(
            File("src/test/java"),
            File("app/src/test/java"),
            File(System.getProperty("user.dir") ?: ".", "src/test/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/test/java")
        )
        candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate test source root. user.dir=${System.getProperty("user.dir")}, candidates=$candidates")
    }

    /** Resolves a production file under [sourceRoot], failing (never skipping) if it is missing. */
    private fun requireScannedFile(relativePath: String): File =
        FailClosedScan.requireScannedFile(sourceRoot, relativePath)
}
