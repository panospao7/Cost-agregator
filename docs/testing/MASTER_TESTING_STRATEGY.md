# Master Testing Strategy — Post-Refactoring

> **Date:** 2026-05-12  
> **Context:** Major refactoring complete (all P0/P1 pipeline + engine issues fixed). Test suite has 76+ compilation failures from constructor signature changes. Need to rebuild test infrastructure that validates the NEW architecture.  
> **Related docs:**  
> - `docs/testing/testing guide.md` — Stress test expansion plan (still useful for stress scenarios)  
> - `docs/testing/TESTING_ASSESSMENT.md` — Coverage overview and strengths  
> - `docs/testing/testing findings new/updated-master-testing-plan-v2.md` — Schema verification + CI gaps  
> - `docs/architecture/LEGAL_PATHS.md` — The ONE allowed path per operation  
> - `docs/architecture/ENGINE_INTERACTION_MAP.md` — What breaks when you touch an engine

---

## Core Principle

> **A test should verify EXPECTED BEHAVIOR, not implementation details.**

- ❌ Bad: `verify(expenseDao.insert(any())).called(1)` — proves nothing about correctness
- ✅ Good: `after creating expense, dashboard total increases by that amount` — proves the pipeline works
- ❌ Bad: `assert(result != null)` — validates passing state with broken infrastructure
- ✅ Good: `assert(result.currency == homeCurrency && result.amount == converted)` — validates expected output

---

## Phase 1: Fix Compilation (IN PROGRESS)

**Goal:** All 191 test files compile.  
**Method:** Mechanical constructor updates — match new signatures.  
**Rule:** Do NOT fix failing assertions yet. Just make them compile.

---

## Phase 2: Triage Failing Tests

After compilation, run all tests and categorize failures:

| Category | Action |
|----------|--------|
| **Intentionally changed behavior** | Delete or rewrite test to match new behavior |
| **Bug in test** (wrong mock setup) | Fix the test |
| **Bug in production** (test caught a real issue) | Fix production code |
| **Obsolete test** (tests removed/deprecated API) | Delete |

---

## Phase 3: Add High-Value Tests

### Priority 1: Golden Scenario Tests (10-15 tests)

These verify complete pipeline flows end-to-end. They are the MOST valuable tests because they catch cross-boundary regressions.

```kotlin
// File: app/src/test/java/com/yourname/expensetracker/golden/

@Test fun `notification_card_purchase_creates_expense_updates_dashboard`() {
    // Given: Revolut notification with €45.99 card purchase
    // When: NotificationProcessingPipeline.process(notification)
    // Then:
    //   - RawNotification inserted with dedupeFingerprint
    //   - Expense created via TransactionLifecycleCoordinator
    //   - TransactionEvent.CREATED written
    //   - Budget monitor triggered (if budget exists)
    //   - Recurring matching attempted
    //   - Dashboard spending total increases by €45.99
}

@Test fun `receipt_scan_creates_expense_links_receipt_updates_analytics`() {
    // Given: Camera receipt image with parsed total $23.50
    // When: ReceiptLifecycleCoordinator.processReceiptInput(uri)
    // Then:
    //   - ScannedReceipt inserted with createdAt != 0
    //   - ReceiptEvent.RECEIPT_SAVED written
    //   - If auto-match found: ReceiptExpenseLink created
    //   - If no match: expense created + linked atomically
    //   - Category breakdown includes the expense
}

@Test fun `recurring_bill_payment_marks_occurrence_paid_suppresses_reminder`() {
    // Given: Monthly Netflix rule with PLANNED occurrence due today
    // When: Manual expense matching Netflix amount/merchant created
    // Then:
    //   - Occurrence status = PAID (atomic claim)
    //   - PlannedExpense status = FULFILLED
    //   - Open reminder deliveries suppressed
    //   - Dashboard does NOT double-count planned + actual
}

@Test fun `email_receipt_duplicate_links_existing_expense_not_creates_new`() {
    // Given: Existing expense from notification for €50 at Amazon
    // When: Email receipt for same €50 Amazon purchase ingested
    // Then:
    //   - No new expense created
    //   - Receipt linked to existing expense
    //   - Result is LinkedExisting, not Success with new expenseId
}

@Test fun `backup_restore_preserves_dashboard_totals`() {
    // Given: DB with 100 expenses totaling €5,000
    // When: createCostBackup() → restoreCostBackup()
    // Then:
    //   - Dashboard spending total still €5,000
    //   - Category breakdown unchanged
    //   - Receipt links preserved
    //   - Recurring state preserved
}

@Test fun `deactivate_recurring_rule_stops_all_future_activity`() {
    // Given: Active monthly rule with 3 future PLANNED occurrences + reminders
    // When: RecurringRuleLifecycleCoordinator.deactivateRule(ruleId)
    // Then:
    //   - Rule.isActive = false
    //   - All PLANNED occurrences cancelled
    //   - All open reminders suppressed
    //   - All PLANNED planned-expenses cancelled
    //   - No future generateOccurrences produces new rows
}

@Test fun `multi_currency_dashboard_shows_converted_totals_not_raw_sum`() {
    // Given: €100 expense + $100 expense, EUR home currency, rate 1.1
    // When: Dashboard spending summary computed
    // Then:
    //   - Total ≈ €190.91 (not €200 raw sum)
    //   - Category breakdown sums to same total
    //   - Daily history sums to same total
    //   - isPartial = false (both rates available)
}

@Test fun `privacy_do_not_store_processes_but_persists_no_raw_text`() {
    // Given: RawStorageMode.DO_NOT_STORE for notifications
    // When: Bank notification with amount in text processed
    // Then:
    //   - Expense created correctly (parser received real text)
    //   - RawNotification.title/text/bigText/extrasJson are all null
    //   - Dedup fingerprint still computed from real text
}

@Test fun `restore_mode_blocks_all_writes_across_all_engines`() {
    // Given: RestoreMaintenanceMode entered
    // When: Attempt expense create, receipt save, recurring generate,
    //        budget add, warranty add, group create, investment add
    // Then: ALL throw/return blocked result
}

@Test fun `concurrent_expense_creation_does_not_double_link_occurrence`() {
    // Given: PLANNED occurrence for Netflix €15
    // When: Two expenses matching Netflix €15 created simultaneously
    // Then:
    //   - Only ONE links to the occurrence (atomic claim)
    //   - Other gets no-match result
    //   - Occurrence has exactly one linkedExpenseId
}
```

### Priority 2: Currency Consistency Tests (5-8 tests)

```kotlin
// File: app/src/test/java/com/yourname/expensetracker/contracts/CurrencyConsistencyTest.kt

@Test fun `spending_summary_total_equals_sum_of_daily_history`()
@Test fun `category_breakdown_percentages_sum_to_100`()
@Test fun `category_breakdown_total_equals_spending_summary_total`()
@Test fun `weekly_drilldown_sum_equals_monthly_total`()
@Test fun `budget_spent_uses_same_rate_basis_as_dashboard`()
@Test fun `forecast_planned_expenses_are_in_home_currency`()
@Test fun `export_conversion_fields_match_dashboard_totals`()
```

### Priority 3: Contract Violation Tests (prove WHY legal paths exist)

```kotlin
// File: app/src/test/java/com/yourname/expensetracker/contracts/LegalPathViolationTest.kt

@Test fun `expense_created_outside_coordinator_has_no_transaction_event`() {
    // Directly insert via DAO → verify no TransactionEvent exists
    // This proves the coordinator is necessary
}

@Test fun `receipt_linked_outside_link_service_has_no_receipt_event`() {
    // Directly update ScannedReceipt.expenseId → verify no ReceiptEvent
}

@Test fun `recurring_rule_deleted_outside_coordinator_leaves_orphan_reminders`() {
    // Directly delete rule via DAO → verify reminders still exist
    // This proves the coordinator cleanup is necessary
}

@Test fun `notification_processed_with_redacted_text_still_parses_correctly`() {
    // Process with STORE_REDACTED → verify expense amount is correct
    // This proves the ephemeral/storage separation works
}
```

### Priority 4: Existing Contract Tests (already created)

```
app/src/test/java/com/yourname/expensetracker/contracts/
├── PrivacyStorageContractTest.kt      — DO_NOT_STORE exhaustive handling
├── MoneyContractTest.kt               — No raw effectiveAmount sum
├── LifecycleBarrierContractTest.kt    — All coordinators check barrier
├── SideEffectContractTest.kt          — No dispatch inside withTransaction
└── RecurringDeactivateContractTest.kt — Rule deactivation cleans up
```

These are static-analysis tests (read source files). Keep them as CI guards.

---

## Phase 4: Stress & Performance Tests

Refer to `docs/testing/testing guide.md` for the full stress test expansion plan. Key additions:

- **SynthesisEngine** with 10,000+ expenses and 500+ recurring patterns
- **CurrencyConverter** with 50+ currency pairs and stale/missing rates
- **NotificationProcessingPipeline** with 1,000 rapid notifications
- **BackupRestore** with 50,000+ row database

---

## Phase 5: CI Integration

From `docs/testing/testing findings new/updated-master-testing-plan-v2.md`:

- Fix Room schema verification (currently behind at v92, app is v124)
- Add `forkEvery` and max heap config
- Split CI into: fast (contracts + golden) / integration / nightly (stress)
- Add ignored-test-count guardrail
- Wire schema check into `check` task

---

## Test Categories & When to Run

| Category | Count | Run When | Purpose |
|----------|-------|----------|---------|
| **Contract tests** (static analysis) | 5 | Every commit | Architecture rules |
| **Golden scenarios** | 10-15 | Every PR | Cross-pipeline correctness |
| **Currency consistency** | 5-8 | Every PR | Financial accuracy |
| **Unit tests** (existing) | 181 | Every commit | Individual component logic |
| **Stress tests** | 10+ | Nightly | Performance/memory |
| **Schema migration** | per-version | Release | DB compatibility |

---

## What NOT to Test

- ❌ Mock-heavy tests that prove nothing about real behavior
- ❌ Tests that validate "no crash" without checking output correctness
- ❌ Tests for deprecated/dead code paths
- ❌ UI screenshot tests (low value for backend-heavy app)
- ❌ Tests that duplicate what the compiler already checks (type safety)

---

## Definition of Done (for test suite)

```
- All 191 test files compile
- Contract tests pass (5 static analysis)
- Golden scenario tests pass (10-15 end-to-end)
- Currency consistency tests pass (5-8)
- No test validates passing state without checking expected output
- Every coordinator has at least one "bypass proves it's needed" test
- CI runs contracts + golden on every PR
- Stress tests run nightly
- Schema verification matches DB version 124
```
