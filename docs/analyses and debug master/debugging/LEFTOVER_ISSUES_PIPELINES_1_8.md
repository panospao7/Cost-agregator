# Leftover Issues — Pipelines 1-8

> **As of:** 2026-05-11 (commit `339cdbdf`)  
> **Context:** All P0 and P1 issues are FIXED. These are P2/P3 enhancements, architectural refactors, and items marked as TODOs in code because they require larger design work that would risk breaking the build if done hastily.

---

## Why Some Items Are TODOs Instead of Fixes

These fall into 3 categories:

1. **Architectural refactors** — Require new classes, interfaces, or restructuring multiple files. Can't be done safely in a single pass without dedicated design + testing.
2. **Feature enhancements** — The current behavior works but could be better. Not bugs.
3. **Static enforcement** — Need CI tooling (Detekt rules, ArchUnit tests) that doesn't exist yet.

---

## Pipeline 1 — Notification Capture

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P1-006 | Enhancement | Service-level drops not written to durable diagnostics (only Timber) | Needs new `NotificationCaptureDiagnostics` writer injected into service |
| P1-007 | Architectural | Shutdown durability — no durable intake before async processing | Needs `NotificationIntake` table + worker-based resume pipeline |
| P1-008 | Architectural | Listener lifecycle uses risky FGS restart model | Needs Android-version-specific testing; works on current targets |
| P1-011 | Enhancement | Pipeline fallback currency regex narrower than filter (3 vs 15 currencies) | Needs shared `NotificationAmountSignalDetector` |
| P1-012 | By Design | Unknown packages skip AI fallback | Intentional privacy/cost control |
| P1-013 | Enhancement | Pending review context loses combined text (uses `text ?: bigText`) | Low priority UX improvement |
| P1-017 | Enhancement | Parser invoked twice for provenance detection | Needs `ParseOutcome` return type from registry |
| P1-019 | Architectural | Service has too many responsibilities | Long-term decomposition into 6+ classes |

---

## Pipeline 2 — Transaction Lifecycle

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P2-001 | Static Enforcement | Raw ExpenseDao mutation surface public, no CI guard | Needs Detekt/ArchUnit rule + allowlist |
| P2-002 | Architectural | Deprecated `createExpense(request, SideEffectMode)` still public | Making it internal risks breaking DI; needs careful migration |
| P2-005 | Enhancement | STANDARD/BULK insert race conflicts don't resolve existing ID | Only STRICT mode resolves; others return generic InsertConflict |
| P2-013 | Enhancement | CreateExpenseRequest source-link fields not persisted in TransactionEvent metadata | Needs schema decision on where to store |
| P2-014 | Enhancement | Business/tax update API semantically partial (some params are no-ops) | Needs DB schema expansion for full tax fields |
| P2-015 | Enhancement | Category-id bulk update is non-atomic (loops one-by-one) | Needs single-transaction bulk approach |
| P2-017 | Enhancement | TransactionEventDao queries thin (no getBySource, getBetween, etc.) | DAO expansion — low risk but low priority |
| P2-019 | Enhancement | Manual expense recommendation uses synthetic Expense not persisted | Needs post-commit reload by ID |

---

## Pipeline 3 — Receipt/OCR/Email

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P3-005 | Architectural | Receipt insert + metadata/event still two-phase (not fully atomic) | Needs repository to return draft, coordinator to own insert |
| P3-006 | Enhancement | Pre-OCR hash (URI) inconsistent with stored hash (compressed file) | Needs dual-hash fields |
| P3-008 | Enhancement | No unique fingerprint DB constraints (race window for duplicates) | Needs partial unique indexes or fingerprint claim table |
| P3-014 | Static Enforcement | Direct ReceiptRepository match/link methods bypass lifecycle | Needs DeprecationLevel.ERROR + migration of callers |
| P3-015 | Enhancement | Direct receipt delete leaves orphan links (no FK) | Lifecycle coordinator is preferred path; direct delete is debug-only |
| P3-016 | Architectural | Bank statement import not atomic or resumable | Needs per-row import state table |
| P3-022 | Enhancement | Validation failures and early exits lack durable receipt diagnostics | Needs PipelineDiagnosticEvent for receipt_input stage |
| P3-023 | Enhancement | Exact-hash duplicate ghost cleanup leaks asset file | Needs asset deletion in ghost cleanup path |

---

## Pipeline 4 — Recurring/Bill Reminders

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P4-007 | Architectural | Rule CRUD not coordinator-owned (repositories call DAO directly) | Needs `RecurringRuleLifecycleCoordinator` with atomic event+mutation |
| P4-011 | Enhancement | Same actual expense can satisfy multiple recurring rules | Needs global match claim table or unique partial index |
| P4-013 | Static Enforcement | Direct recurring DAO mutation surface public | Needs Detekt/ArchUnit rule |
| P4-016 | Enhancement | Reminder failure state too thin (no attemptCount, retryAt) | Needs delivery_attempt columns or table |
| P4-017 | Enhancement | Snooze/dismiss receivers use runBlocking, bypass coordinator | Needs goAsync() + coordinator delegation |
| P4-018 | Enhancement | updateOccurrenceStatus accepts arbitrary strings | Needs enum/sealed type |
| P4-019 | Enhancement | RecurringLifecycleEvent schema can't query by ruleId/deliveryId | Needs nullable ruleId/deliveryId columns |
| P4-020 | Enhancement | Lifecycle metadata JSON built by string interpolation (can be invalid) | Needs JSONObject builder |
| P4-021 | Enhancement | Recurring rule validation weak (no amount/currency/merchant checks) | Needs validation in coordinator |
| P4-022 | Enhancement | Reminder defaults infrastructure-driven, not user-policy-driven | Needs ReminderSettingsRepository |
| P4-023 | Enhancement | Reminder scheduledAt lacks local time/quiet hours | Needs time-of-day + timezone policy |
| P4-024 | Enhancement | Recurring matching too strict for real bill payments | Needs confidence/grace-window policy |
| P4-025 | Enhancement | Planned projections not updated after rule edits | Needs update-open-planned logic |
| P4-026 | Enhancement | Reminder suppression has no event/count | Needs REMINDER_SUPPRESSED_PAID event |
| P4-028 | Needs Verification | Old occurrenceKey migration/backfill not verified | Needs migration test |

---

## Pipeline 5 — Currency/Dashboard/Analytics

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P5-001 | Architectural | Historical aggregate uses period midpoint, not per-transaction rates | Per-tx is expensive; midpoint is acceptable approximation |
| P5-002 | Enhancement | Historical fallback to latest rate without warning/partial flag | Needs RATE_BASIS_FALLBACK warning type |
| P5-003 | Enhancement | getRate orders by lastUpdated not validDate (edge case with imported historical rates) | Needs explicit latest-current-rate query |
| P5-004 | Enhancement | Weekly/daily MoneyAggregate transaction counts are zero | Needs `transactionCounts` param passed to builder |
| P5-005 | By Design | Weekly/daily/monthly use latest-rate (current-valuation drilldown) | Documented as acceptable for current-valuation widgets |
| P5-007 | Architectural | Category breakdown vs summary use different FX bases | Needs unified NormalizedAnalyticsInput for both |

---

## Pipeline 6 — Budget/Forecast/Cashflow

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P6-001 | Architectural | Budget limit (period-end rate) vs spend (latest rate) different FX bases | Needs historical per-tx spend for budget actuals |
| P6-002 | Architectural | BudgetMonitor adjusted-spend only in ViewModel, not repository | Needs SharedExpenseBudgetOffsetEngine in BudgetRepository |
| P6-003 | Enhancement | Budget alert currency label may mismatch amounts | Needs explicit status.currency pass to sendNotification |

---

## Pipeline 7 — Backup/Restore

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P7-001 | Architectural | Stale injected Room instance after DB swap | Mitigated by forced restart; proper fix needs RestoreDatabaseOpener |
| P7-002 | Enhancement | Maintenance mode not a global DB-level write interceptor | Convention-based via DatabaseWriteBarrier; no Room interceptor exists |
| P7-003 | Enhancement | Backup uses file copy after checkpoint, not SQLite backup API | Acceptable with BACKUP_EXPORTING mode blocking writes |
| P7-004 | Enhancement | Receipt asset restore not resumable (no per-asset ledger) | Crash during assets doesn't rollback DB (by design) |
| P7-005 | Enhancement | Restore doesn't prove semantic equivalence (only row counts) | We added orphan-link checks; full aggregate comparison needs test fixtures |

---

## Pipeline 8 — Privacy/AI/Redaction

| ID | Type | Description | Why TODO |
|----|------|-------------|----------|
| P8-006 | Enhancement | Retention scope: AI chat messages and debug diagnostics not yet purged | Needs chat/diagnostics DAO purge methods |
| P8-008 | Enhancement | Purpose-aware redaction has no PreparedCloudPayload contract | Per-purpose rules exist but no typed payload enforcement |
| P8-010 | Static Enforcement | No compile-time guarantee all geocoding providers check privacy | Self-check exists; needs ArchUnit test for enforcement |
| P8-012 | Enhancement | Privacy-denied UI states only in PrivacySettingsScreen, not feature screens | Needs PrivacyBlockedBanner propagation to 4+ ViewModels |

---

## Summary

| Category | Count | Description |
|----------|-------|-------------|
| Architectural Refactors | 12 | Need new classes/interfaces/restructuring |
| Enhancements | 35 | Current behavior works, could be better |
| Static Enforcement | 5 | Need CI tooling (Detekt/ArchUnit) |
| By Design | 2 | Intentional behavior, not bugs |
| Needs Verification | 1 | Migration test needed |
| **Total** | **55** | |

None of these are bugs that corrupt data or violate privacy. They are all improvements to observability, robustness, UX, and architectural cleanliness.
