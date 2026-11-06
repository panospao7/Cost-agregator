# Owner item 144: scoping notes (explorers, read at mainline 80251ea3)

## A. Category delete and rename (T3: money and lifecycle)
- Existing code:
  - `CategoryDao.mergeCategories` (:187, one @Transaction) and `CategoryRepository.mergeCategories` (:156, barrier-checked, no caller).
  - `deleteCategory` (:207) refuses defaults and refuses when budgets exist. It does not reassign.
  - There is no rename query; `@Update update` is at :47.
- Legal-path problem: `CategoryDao.reassignExpensesToCategory` (:123) updates expenses directly, with no TransactionEvent and no post-commit side effects. `ExpenseDao.updateCategoryForCategory` (:361, restricted) has no callers.
- Plan:
  - Add a new TLC method `reassignCategoryBulk(old, new)`: one BULK_UPDATED event plus bulk post-commit, following `bulkUpdateCategory` at :2749.
  - Delete: reassign through TLC, move the other tables, then delete, all in one transaction. The direct expense UPDATE is removed from mergeCategories.
- Missing references to add: ManualRecurringExpense, InstallmentPlan, BankConnection.defaultCategoryId, ReceiptItemCategorization.userCorrectedCategoryId. MerchantCategory is CASCADE, so it must be reassigned first. Budget is RESTRICT.
- Rename: `CategoryRepository.renameCategory`. It trims, validates, rejects a NOCASE duplicate, refuses defaults, and invalidates the cache and the classifier.
- UI: `CategoryScreen` gets an overflow menu (rename and delete), a delete dialog with a count, and a replacement picker defaulting to Uncategorized. About 12 strings.
- Policy rows are needed (item 143 allows them). `DbGuardPolicyFixtureTest:1778` lists the CategoryRepository entries.
- Defaults (item 143/144):
  - The picker defaults to Uncategorized.
  - Budgets on a deleted category merge into the target through the existing deactivate-conflict logic.
  - Default categories cannot be renamed.

## B. CSV import UI, J-12-2 (T3: privacy and money)
- Existing code:
  - `util/CsvExpenseImporter.kt:73` (barrier-checked; per row TLC.createExpense with CSV_IMPORT; dedupe; stripFormulaNeutralizer).
  - The only UI is on the debug screen.
- Gaps:
  - Failure strings carry raw cell text and e.message (:179, :231, :282, :289). These must become bounded codes.
  - There is no preview mode and no size cap.
  - `getOrCreateCategory` (:339) calls the deprecated `categoryDao.insert` directly.
- Plan:
  - A section on the Export screen.
  - OpenDocument, then a write-free `preview()` with counts, a confirm step, a cancellable commit, and a result screen.
  - Unknown categories map to Uncategorized.
  - 5 MB cap. CSV only. The debug import stays.
  - About 16 strings.
- Order: A before B (B reuses `CategoryRepository.addCategory`).

## E. Refunds netting, DR-05 (T3: money, schema)
- Today:
  - There is no REFUND type. Refunds arrive as DEPOSIT (bank API :87, statement parser :485, notification prompt).
  - Spending SQL is PURCHASE-only, so refunds never reduce spend.
  - `RefundLikelihoodDetector` (same amount, merchant and currency, within 90 days) only shows a notice on Transactions.
  - `spendingExclusionReason` zeroes a whole row; it cannot subtract part of one.
- Proposed: a new `ExpenseRefundLink` table (refund→purchase, appliedAmount), giving schema 152→153 with a non-destructive migration.
  - effectiveAmount(purchase) = max(0, amount − Σ applied).
  - TLC owns link and unlink.
- Consumers: everything reading SPENDING_PREDICATE or EFFECTIVE_AMOUNT, plus normalization, totals, budgets, the forecast chain and exports.
- Needs a domain-modeler spec first. Open questions:
  - partial and multiple refunds;
  - currency;
  - income double count;
  - which month gets the refund;
  - auto-link;
  - delete and restore;
  - export.

## F. Spare change, BL-04 (T1: engine with no production caller)
- `AutomatedSavingsRuleEngine.kt:207-235`: SPARE_CHANGE saves the whole purchase when it is between 1 and 10. ROUND_UP (:131-205) rounds to `roundUpTo` (default 5).
- The engine has no production caller, so no visible numbers change today.
- Fix: SPARE_CHANGE = ceil(x)−x in cents (BigDecimal), skipped when the remainder is 0. It works in the expense's own currency, matching ROUND_UP (default).
- Tests: 3.40→0.60, 3.00→none, ≤0, not-mine/shared, the monthly cap.

## G. I-01-1 strict barrier rule: PARKED again, because it is bigger than what the owner approved
- 463 policy rows: 32 direct, 415 helper, 16 workerMediated. The dominance proof runs only for groups with a direct member (`policy_v2_evidence.py:1096-1114`).
- A prior attempt (fix-1.0.4-review-findings-handoff.md:268-270, :366) surfaced 115 unproven groups. db_access exited 2 and known_good_state cascaded, so the change was reverted.
- A local-dominance rule would need a frozen, shrink-only baseline of about 100-120 groups. That is NOT the "short exemption list for two safe workers" the owner approved in item 144. A call-graph proof engine (`inspect_db_mediation_proof.py`, GR-14/15) would shorten it, but it is missing from the tree.
- Decision: do not implement. Report to the owner with the staged option: (1) one-hop caller proof plus a workerMediated checkpoint rule; (2) a shrink-only baseline of about 120 entries seeded from the generated failures plus the 20 GR-14 owner_accepted rows.

## H. I-04-5 RawQuery builder pin: GO (tightening)
- The 4 @RawQuery DAO methods are already pinned in db_raw_query_classification.yml. The builders are not.
- ExpenseRepository has exactly 4 `SimpleSQLiteQuery(` sites: :275, :303, :343, :378. They are fed by `buildExpenseDynamicQueryParts` (:381), with select literals "SELECT e.*" ×2 and "SELECT COUNT(*)" ×2.
- Add rule G-SPENDING-PREDICATE-04 to `scripts/verify_spending_predicate_usage.py`, with the recorded set in a new `config/guards/spending_raw_query_builders.yml`. Any extra or missing entry fails.
- Register the yml in `guard_registry.py` requiredInputs (:810). Tests go in `scripts/test_verify_spending_predicate_usage.py`.
- Validate: pytest, then registered guards spending_predicate_usage and guard_docs_truth, then static-guards.

## C. Raw DB export on the recovery screen, J-20-1 (T2/T3: privacy)
- `MainActivity.kt:451-472` CriticalRecoveryRequired already has "Close app" and an app-settings button. RestoreInProgress (:473-481) is left alone.
- `ui/unsupported/UnsupportedDatabaseActivity.kt` (not Hilt) already has the export: auth gate, then an AuthPromptCopy.EXPORT device prompt, then the SAF zip of the DB and -wal, with constant log codes only.
  - Its gate is `onCreate` :101 and `writeBackupTo` check :357, which require `blockedReasonOrNull != null`.
- Catch: CriticalRecoveryRequired is a restore-maintenance mode, not corruption, and Room may be open. The raw copy is not a consistent snapshot, so the UI must say "raw copy, may be incomplete".
- Plan:
  - Launch UnsupportedDatabaseActivity for a recovery export.
  - Relax its gate to: gate blocks OR the persisted mode is CRITICAL_RECOVERY_REQUIRED, read through a narrow Room-free entry point (fail-closed). The intent extra is only a hint.
  - Offer it only when the DB file exists and is non-empty.
  - About 2 strings.

## D. Notification capture skip list, P-01-3 (T2: privacy, worker)
- The backend already exists: `blocked_packages` table, BlockedPackageDao, `NotificationRepository.blockPackage/unblockPackage/getBlockedPackages` (:157-175, barrier).
  - `NotificationCaptureGate` step 6 (~:259) runs before text extraction, fails closed, and has a live observer.
  - The deferred path re-checks at :554. The table is in the backup verifier.
  - The only UI is on the debug screen.
- **Privacy gap:** `NotificationIntakeWorker` (:480) does not re-check the blocked list, so rows queued before a block still become reviews. Fix: apply the same predicate in the worker and drop queued rows for that package.
- UI: a sub-screen under Privacy settings.
  - It lists blocked apps with unblock, and candidate apps seen in captured data (`NotificationRepository.getAllPackages`).
  - App labels come from getApplicationInfo, falling back to the package name. No QUERY_ALL_PACKAGES (no new permission).
  - About 8-10 strings.
- Defaults:
  - A block affects future captures plus drops queued intake rows. Already-saved data is not purged.
  - Show a warning when blocking an app that has a bank parser.
