# RP-04 — Recurring/subscription lifecycle (Pipeline 4)

> **Scope class:** pipeline + crossovers (architecture guard, calendar utils). **Mode:** strict (recurring lifecycle is on the strict list).
> **Files:** `SubscriptionManagementRepository` ⟂, `SubscriptionManagementViewModel` ⟂, `SubscriptionManagementScreen` ⟂, `RecurringRuleLifecycleCoordinator` (domain/recurring/lifecycle/), `RecurringLifecycleCoordinator` (domain/recurring/lifecycle/), `RecurringOccurrenceDao` / `RecurringReminderDeliveryDao` (data/database/dao/), `RecurringOccurrenceExpander` (domain/recurring/), `TimePeriodUtils` (domain/util/), `RecurrenceCalculator` ⟂, `ManualRecurringExpenseDao` ⟂, `RecurringArchitectureGuardTest` (test/architecture/), `BillReminderWorker` (service/reminder/).
> **PR split:** (4a) routing = P4-001 + P4-002 (+guard); (4b) semantics = P4-003 + P4-005; (4c) dead/latent = P4-004 + P4-006 (or fold into RP-20 per D4).

---

## P4-001 — Subscription delete bypasses the coordinator → ghost notifications (HIGH)

**Problem.** `SubscriptionManagementRepository.deleteSubscriptionById` (`:49-52`) does raw `subscriptionDao.deleteById` + barrier. `RecurringOccurrence` has no FK to the rule, `PlannedExpense` only to Category, and the delivery CASCADE fires only on occurrence deletion → pre-materialized PLANNED occurrences + SCHEDULED deliveries survive a subscription delete and keep firing bill reminders (the pending-deliveries query matches on occurrence status only — no rule join, no isActive check).

**Fix design.**
1. Delete the raw body; delegate exactly like the verified template `ManualRecurringExpenseRepository.deleteById` (`data/repository/ManualRecurringExpenseRepository.kt:43-45`) does:
   ```kotlin
   suspend fun deleteSubscriptionById(subscriptionId: Long) {
       ruleLifecycleCoordinator.get().deleteRule(subscriptionId)
   }
   ```
   Inject `dagger.Lazy<RecurringRuleLifecycleCoordinator>` (mirroring `ManualRecurringExpenseRepository.kt:15`) to avoid the repository→coordinator cycle. `deleteRule`'s real signature is `suspend fun deleteRule(ruleId: Long)` returning **Unit** (`RecurringRuleLifecycleCoordinator.kt:93`) — no Result mapping exists; a missing rule is a silent no-op (already idempotent for double-tap). It purges `reminderDeliveryDao.deleteByOccurrenceIds` + `plannedExpenseDao.deleteByRecurringRuleId` + `occurrenceDao.deleteBySource` + rule delete + `"RULE_DELETED"` event inside one `withTransaction` with barrier (`:93-125`).
2. Subscriptions and manual rules share the `manual_recurring_expenses` table and `RECURRING_RULE` source type (`SubscriptionManagerEngine.kt:191,195`) — **no** source-type special-casing needed in the coordinator.

**What it solves.** Deleting a subscription removes its future occurrences, deliveries and planned rows atomically — no more ghost bill reminders.

**Guardrails.**
- Lifecycle legal path: this is the whole point — no new direct-DAO paths.
- Do not add FKs as the fix (schema change + destructive risk; the coordinator purge is the established pattern).
- Idempotency: double-tap delete → second call returns not-found → UI success (same as today's raw delete semantics).
- Crossover: RP-02's guard upgrade must recognize `subscriptionDao` as an injected `ManualRecurringExpenseDao` — after this fix the repository keeps only read paths + toggle (see P4-002), so re-grep callers.

**Tests.** In `RecurringLifecycleCoordinatorTest` / a new `SubscriptionDeletionTest`: delete → assert zero occurrences (any status), zero deliveries, zero planned rows, one `"RULE_DELETED"` event (`RecurringRuleLifecycleCoordinator.kt:113`); reminder query returns nothing afterwards. UI test: `SubscriptionManagementViewModelTest` delete flow.

## P4-002 — Subscription toggle bypasses activate/deactivate cascade (HIGH)

**Problem.** `toggleSubscriptionStatus` (VM `:256-271`) writes `isActive` via raw `subscriptionDao.update`. `deactivateRule`'s cascade (`RecurringRuleLifecycleCoordinator.kt:55-88` — deletes **all** open PLANNED occurrences including past-due, per the inline comment at `:70-72`; its KDoc says "cancels" while the code deletes — fix the KDoc verb in this PR) never runs; neither dispatch query checks rule `isActive` → reminders continue after deactivation. The manual-recurring screen's identical toggle is correct (routes through the repository → coordinator), so the two screens disagree. Sub-claim note: price edits are *not* affected (`recordPriceChange` routes through `updateRule`); the VM's category-label write (`:325`) is display-only.

**Fix design.**
1. In `SubscriptionManagementRepository`: replace the toggle's raw update with:
   ```kotlin
   suspend fun setSubscriptionActive(subscriptionId: Long, isActive: Boolean) =
       if (isActive) ruleLifecycleCoordinator.activateRule(subscriptionId)
       else ruleLifecycleCoordinator.deactivateRule(subscriptionId)
   ```
   Keep the UI's optimistic state flip + error revert as-is; map coordinator not-found → revert.
2. The display-label write (`VM:325`, `subscriptionCategory`) may stay a raw update **only if** it is truly display-only — verify no engine consumer reads it; add a KDoc on the repository method stating it is intentionally outside the lifecycle (occurrences don't consume it).
3. Guard: extend `RecurringArchitectureGuardTest:48-55` (or via RP-02's class-level scan) to flag any non-repository class touching `ManualRecurringExpenseDao` write methods — the `subscriptionDao` alias must be detected.

**What it solves.** Deactivating a subscription stops its reminders and cancels future occurrences identically to the manual screen; activation regenerates.

**Guardrails.**
- `deactivateRule` **deletes** (not cancels) planned rows per its KDoc — acceptable, documented; do not invent a soft-cancel here.
- Re-activation after deletion of future occurrences regenerates from `maxOf(nextDate, startOfToday)` — verify no duplicate reminders for the current window (deliveries were deleted with occurrences).
- Keep `isActive` flip atomic with the coordinator call from the user's perspective: call coordinator first, update UI state on success.

**Tests.** `SubscriptionToggleTest`: toggle off → occurrences/deliveries for that source gone + rule inactive event; toggle on → future occurrences regenerated, no duplicates; BillReminderWorker pending query returns nothing for the off state.

## P4-003 — `updateRule` silently destroys past-due unpaid occurrences (HIGH)

**Problem.** `RecurringRuleLifecycleCoordinator.kt:286-303` deletes **all** open PLANNED occurrences (`getPlannedIdsBySource` has no date filter) but regenerates only from `maxOf(normalized.nextDate, getStartOfDay(now))` → a past-due-but-unpaid occurrence (the OVERDUE-window case) is deleted and never re-created. The only past-window regeneration path (`ensureOccurrencesGeneratedForReconciliation`, `RecurringLifecycleCoordinator.kt:1159-1177`) has **zero production callers**.

**Fix design.**
1. Date-bound the deletion: add `RecurringOccurrenceDao.getPlannedIdsBySourceSince(sourceType, sourceId, fromMs)` (`WHERE status='PLANNED' AND dueDate >= :fromMs`) and use `fromMs = regenerateStart` (the same `maxOf(normalized.nextDate, startOfOfDay(now))` already computed at `:299-302`). Delete deliveries only for those ids.
2. Leave past-due PLANNED occurrences untouched: they keep the **old** amount (correct — they are already-incurred history), remain claimable (`claimForExpense` semantics unchanged), and their OVERDUE deliveries keep firing.
3. If the edit moved `nextDate` **backwards** into the past-due zone (e.g. user fixes a wrong date), the untouched old occurrence may now coexist with a regenerated one near it — accept: amounts differ, keys differ (`|dayStart|` embedded), user sees both; the older one remains claimable and the PAID claim marks exactly one. Document this in the KDoc.
4. KDoc correction on `updateRule` ("deletes open occurrences **on/after** the regeneration start").

**What it solves.** Editing a rule no longer erases overdue unpaid bills.

**Guardrails.**
- No schema change (new DAO query only).
- Do **not** attempt to resurrect already-deleted past occurrences for existing users (data gone; out of scope — note in PR description).
- Money rule: regenerated occurrences use the new amount; past-due keep the old — intentional, add a comment.
- Crossover: `expandDetectedPatterns`/stress engine reads PLANNED occurrences regardless of date — past-due ones now persist and will legitimately re-enter stress/cashflow windows (correct behavior; verify FSFE's ACTIVE_OCCURRENCE_STATUSES handling includes OVERDUE — it does, `:285`).

**Tests.** *(new file — `RecurringRuleLifecycleCoordinatorTest` does not exist yet; create it)*: rule with occurrences at D-5 (planned), D+2, D+9 → edit amount → assert D-5 survives with old amount, D+2/D+9 deleted and regenerated with new amount, deliveries only for the regenerated pair; claim on the survivor still works.

## P4-005 — Month-end anchor drift (MED)

**Problem.** `RecurringOccurrenceExpander.advance` (`:161-171`) chains `TimePeriodUtils.addMonths` (Calendar clamping, `TimePeriodUtils.kt:1344-1348`): Jan-31 → Feb-28 → **Mar-28 forever**. `RecurrenceCalculator:107` drifts `rule.nextDate` identically. No re-anchor exists.

**Fix design (anchor source decision required — see D7 amendment).**
1. Add `TimePeriodUtils.advanceMonthAnchor(anchorDayOfMonth: Int, fromMs: Long, months: Int): Long` — `YearMonth.from(Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()).plusMonths(months).atDay(min(anchorDayOfMonth, lengthOfMonth())).atStartOfDay(zone)...toEpochMilli()` (use `YearMonth.from(...)` directly — **no** `YearMonth.fromDate` helper exists; align with `getStartOfMonth`/`getEndOfMonth`/`getMonthRange` at `TimePeriodUtils.kt:713/:744/:781`).
2. **Anchor source — verified constraint:** `ExpandRequest` (`RecurringOccurrenceExpander.kt:43-54`) carries only `anchorDate` (= the rule's `nextDate` at all three coordinator call sites, `RecurringRuleLifecycleCoordinator.kt:150/:210/:314`), and `ManualRecurringExpense` has **no persisted start-date/anchor field** — so "the rule's original anchor day" does not exist anywhere today. Choose:
   - **Option A (no schema change — recommended):** derive `anchorDom = dayOfMonth(anchorDate)` once per `expand()` call and advance via the helper from each occurrence's calendar month. This **stops future drift cold** (Feb-31 rules no longer degrade after February), but a rule whose `nextDate` has *already* drifted (e.g. sitting on the 28th) re-anchors at 28 — the D7 "snap back to the original day" guarantee does **not** hold for already-drifted rules.
   - **Option B (schema change — explicitly NOT budgeted here):** persist `anchorDayOfMonth`; restores the original day for drifted rules but requires version+migration+snapshots+tests. Only with stakeholder approval.
   The plan proceeds with **A**; D7's wording is amended accordingly (below).
3. Mirror in `RecurrenceCalculator.addFrequencyInterval` (`:107`) so `rule.nextDate` advances with the same helper (single util, MONTHLY + QUARTERLY — both confirmed clamping, `RecurringOccurrenceExpander.kt:165-166`).
4. Weekly/yearly paths unchanged.

**What it solves.** A rule anchored on the 29th–31st lands on the last valid day of each month, every month, forever.

**Guardrails.**
- **D7 (approved jump):** existing drifted rules snap back to the true anchor on the next occurrence — accepted; call it out in the PR.
- occurrenceKey contains `dayStart` → drifted past keys and corrected future keys coexist without collision; do **not** rewrite historical rows.
- T4C time tests (`TimePeriodUtilsTest` pins single-step clamping as *utility* behavior) — keep `addMonths` as-is; the new helper is additive, no behavior change to existing utils.
- Only the recurring paths adopt the helper; budget period windows (BudgetCalculator) are separate and correct.

**Tests.** *(new file — `RecurringOccurrenceExpanderTest` does not exist yet; create it)*: Jan-31 monthly across a leap year (Jan31→Feb29→Mar31→Apr30→May31); quarterly anchor; year-boundary; a rule created on the 31st whose start month is February (anchor=min(31, lenOfMonth) from start). Golden: `ConcurrentOccurrenceClaimTest` unchanged (keys stay unique).

## P4-004 (LOW, dead) / P4-006 (LOW, latent)

- **P4-004 (corrected):** `RecurringPlanProjectionService.projectFromRule` (`:58`) — zero callers, internally inconsistent (raw `now` vs start-of-day). **Delete the method only** — the service itself is live (injected by `RecurringRuleLifecycleCoordinator.kt:41` and used at `:167/:226/:334` via `projectFromOccurrencesInCurrentTransaction`). Also fix the stale KDoc mention in `SynthesisEngine.kt:36`.
- **P4-006:** `FAILED_PERMISSION` delivery status is a dead end (never reopened, not in TERMINAL_STATUSES) but effectively unwritable (worker diverts permission-denials to `cancelClaimedDelivery`). **Minimal fix:** in `RecurringLifecycleCoordinator.markReminderFailed:1040` stop mapping permission reasons to a bespoke status — route them to `CANCELLED` with reasonCode `PERMISSION_REVOKED` (controlled constant; `reopenDeliveryForOccurrenceWindow` already reopens CANCELLED). Delivery status is a plain **String column** (`RecurringReminderDelivery.kt:31`), not an enum — simply stop emitting the `"FAILED_PERMISSION"` literal (unwritability confirmed: single `markReminderFailed` caller receives only `"notification_error"` post-diversion); if a debug query finds historical rows, add the literal to `TERMINAL_STATUSES` so dismiss/snooze stop treating them as live.

## Hygiene (with RP-20/21)

- `domain/recurring/RecurringLifecycleFixesTest.kt` — empty `@Ignore` stub; delete in RP-20 (superseded by the tests added here).

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*RecurringRuleLifecycle*" --tests "*RecurringLifecycleCoordinator*" --tests "*RecurringOccurrenceExpander*" --tests "*SubscriptionManagement*" --tests "*BillReminderWorker*" --tests "*RecurringArchitectureGuard*"
```

## Sequencing & risk
- Land RP-02 first (guard alias detection), then 4a → 4b → 4c. Risk: 4b is engine math — keep diffs to the coordinator + DAO + expander; no UI changes.

## Verification addendum (2026-09-07 re-evaluation)

All claims re-verified against HEAD. Key facts for the implementer:
- `deleteRule` is `suspend fun deleteRule(ruleId: Long): Unit` (`RecurringRuleLifecycleCoordinator.kt:93`) — no Result; template delegation is `ManualRecurringExpenseRepository.kt:43-45` using `dagger.Lazy` (`:15`).
- Expected event literal is `"RULE_DELETED"` (`:113`).
- Nothing regenerates from a deleted rule: all generation reads the rule by id first (`generateOccurrences` throws if absent, `RecurringLifecycleCoordinator.kt:101-102`); no scheduler iterates rules for generation.
- `deactivateRule` deletes **all** open PLANNED (incl. past-due — status-only `getPlannedIdsBySource`, `RecurringOccurrenceDao.kt:44-45`); after P4-003's date-bounding of `updateRule`, the same past-due deletion remains in `deactivateRule` — arguably intended for deactivation; state it explicitly in the PR.
- `RecurringArchitectureGuardTest` forbidden regexes at `:49-54` (six patterns on receiver `manualRecurringExpenseDao`); exempt files list verified.
- Resolved paths: `SubscriptionManagementRepository` → data/repository/; `SubscriptionManagementViewModel`/`Screen` → ui/screens/subscription/; `RecurrenceCalculator` → domain/logic/; `ManualRecurringExpenseDao` → data/database/dao/.
- Test files verified: `RecurringLifecycleCoordinatorTest` (test/domain/recurring/lifecycle/), `ConcurrentOccurrenceClaimTest` (golden/), `BillReminderWorkerTest`, `RecurringArchitectureGuardTest`, `SubscriptionManagementViewModelTest` exist; `RecurringRuleLifecycleCoordinatorTest` and `RecurringOccurrenceExpanderTest` do **not** (create). `RecurringLifecycleFixesTest` confirmed empty `@Ignore` stub.
