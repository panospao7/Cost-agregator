# Batch 22 — Transactions & Add Expense (H4-H6, M3, L2-L3 + 1 unmapped)

## Technical Plan (Advanced)
### Scope
- In:
  - Transaction list correctness, pagination consistency, edit-flow data integrity, and Add Expense UX hardening for:
    - `H4`, `H5`, `H6`, `M3`, `L2`, `L3`
    - `UNMAPPED-22A` (count reconciliation item)
  - Regression coverage for `TransactionsViewModel`, `AddExpenseViewModel`, and key UI behavior.
- Out:
  - No redesign of transactions information architecture.
  - No new domain features (new filters, new tabs, new save flows).
  - No backend algorithm rewrites outside integrity constraints needed by this batch.

### Complexity Assessment
- Estimated files touched: **8–14**
  - `ui/screens/transactions/TransactionsViewModel.kt`
  - `ui/screens/transactions/TransactionsScreen.kt`
  - `ui/screens/addexpense/AddExpenseSheet.kt`
  - `ui/screens/addexpense/AddExpenseViewModel.kt`
  - `res/values/strings.xml`
  - tests under `ui/screens/transactions/*`, `ui/screens/addexpense/*`
- Risk level: **medium-high**
- Cross-module impact: **yes** (UI, ViewModel orchestration, repository update semantics)

### Batch Plan
1. Batch name: **H4 — Prevent stale page append during rapid ALL-tab filter/search/sort changes**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt`
     - `app/src/test/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModelStressTest.kt`
   - objective:
     - Ensure `loadMore()` cannot append results from outdated query/filter snapshots after state changes.
   - risks:
     - Over-restrictive invalidation could suppress legitimate pagination loads.
   - validation:
     - Stress test with rapid tab/filter/search/sort changes verifies no stale entries appended.

   **Root Cause Analysis**
   - `loadInitialAll()` uses request-id gating, but incremental `loadMore()` does not bind to a stable snapshot key.
   - Under quick state changes, page N can return after filter reset and contaminate current list.

   **Implementation Strategy**
   1. Introduce a pagination snapshot token composed from query, filter, ownership, and sort state.
   2. Capture token at request start; discard completion if token no longer current.
   3. Add test fixtures that intentionally reorder completion timing.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Medium.
   - Mitigation: deterministic test with simulated delayed repository responses.

   **Verification Plan**
   - Unit stress test: rapid state changes while in `ALL`, assert final list only contains current-snapshot rows.
   - Manual QA: repeatedly toggle filters/search and scroll to load-more; verify no old rows appear.

   **Estimated Effort**
   - **Medium**

2. Batch name: **H5 — Fix signed/locale-safe amount rendering in transaction day headers**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt`
     - `app/src/main/res/values/strings.xml`
     - transactions UI snapshot tests
   - objective:
     - Remove hardcoded euro formatting and preserve clear debit/credit semantics.
   - risks:
     - Existing screenshots/golden tests will change.
   - validation:
     - Header amount output respects locale/currency and sign conventions.

   **Root Cause Analysis**
   - `DateHeader` renders `"€${String.format(...)}"` using `abs(totalAmount)`.
   - This can hide sign meaning and locks UI to EUR formatting.

   **Implementation Strategy**
   1. Route header formatting through existing normalized amount formatter utilities.
   2. Keep sign semantics explicit (text and color in sync).
   3. Move any remaining symbols/text fragments to resources.

   **Dependencies**
   - Depends on existing amount formatting utility contracts.

   **Risk Assessment**
   - Medium (UX interpretation and visual regression risk).

   **Verification Plan**
   - Unit/UI assertions for positive and negative totals across locales.
   - Manual check with mixed transaction types.

   **Estimated Effort**
   - **Medium**

3. Batch name: **H6 — Enforce atomic validation for transfer/shared/not-mine edit paths**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt`
   - objective:
     - Prevent partial updates and contradictory ownership/share metadata states.
   - risks:
     - Tightened validation may reject currently accepted malformed combinations.
   - validation:
     - Dialog submission and save paths fail fast on invalid combinations; valid paths update atomically.

   **Root Cause Analysis**
   - Ownership-related edits are split across multiple calls (`updateNotMineDetails`, `updateSharedExpenseDetails`).
   - Multi-step mutation increases risk of partially applied state when one step fails.

   **Implementation Strategy**
   1. Define one canonical validation matrix for ownership/share/transfer metadata.
   2. Consolidate write path into one ViewModel entrypoint and one repository transaction boundary where needed.
   3. Keep old methods as internal wrappers only if needed for compatibility.

   **Dependencies**
   - May depend on repository transaction helper support.

   **Risk Assessment**
   - High (behavioral change + data integrity constraints).
   - Mitigation: migration-safe validation (warn/log first if necessary), strong test matrix.

   **Verification Plan**
   - Unit tests covering mutually exclusive/required fields and successful atomic update case.
   - Manual QA: edit ownership/shared values and verify persisted result consistency.

   **Estimated Effort**
   - **High**

4. Batch name: **M3 — Use lifecycle-aware collection in Transactions/AddExpense composables**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt`
   - objective:
     - Reduce lifecycle edge-case emissions and recomposition churn.
   - risks:
     - Behavioral drift if initial collection timing assumptions exist.
   - validation:
     - Screen state remains correct through pause/resume and navigation back-stack transitions.

   **Root Cause Analysis**
   - Several state flows use `collectAsState()` directly in screen composables.
   - This can over-collect outside active lifecycle windows.

   **Implementation Strategy**
   1. Switch to `collectAsStateWithLifecycle()` for long-lived screen flows.
   2. Keep transient local UI state untouched.
   3. Add regression test around resume behavior where available.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Lifecycle scenario test (or manual): background/foreground with no duplicate side effects.

   **Estimated Effort**
   - **Low**

5. Batch name: **L2 — Localize hardcoded Add Expense expandable-state labels and prompts**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt`
     - `app/src/main/res/values/strings.xml`
   - objective:
     - Remove remaining inline English labels (`expanded/collapsed`, form labels).
   - risks:
     - Missed keys create fallback/inconsistent copy.
   - validation:
     - No hardcoded UI copy remains in targeted screen sections.

   **Root Cause Analysis**
   - Add Expense still includes inline strings in content descriptions and section headings.

   **Implementation Strategy**
   1. Extract literals to resources with consistent naming.
   2. Update semantic descriptions and state labels together.
   3. Add grep-based guard in CI or checklist.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Static grep for remaining literals + UI smoke test.

   **Estimated Effort**
   - **Low**

6. Batch name: **L3 — Make initial Add Expense prefill one-shot and argument-stable**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt`
   - objective:
     - Prevent accidental re-seeding of fields after user edits when screen is recreated.
   - risks:
     - Incorrect guard can block legitimate explicit reseed flows.
   - validation:
     - Prefill occurs once per argument set; subsequent user edits persist.

   **Root Cause Analysis**
   - Prefill currently runs under `LaunchedEffect(Unit)` and relies on composition lifetime assumptions.

   **Implementation Strategy**
   1. Key prefill effect by `initialAmount`/`initialMerchant`.
   2. Track consumed prefills in VM/session state.
   3. Define explicit behavior when new initial args arrive.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low-Medium.

   **Verification Plan**
   - Unit test: prefill then user edit then recomposition; edit remains.

   **Estimated Effort**
   - **Low**

7. Batch name: **UNMAPPED-22A — Reconcile missing 7th issue ID for Batch 22**
   - files:
     - deep-review canonical issue index (external source)
     - this plan file
   - objective:
     - Resolve mismatch (`H4-H6, M3, L2-L3` gives 6 explicit IDs while batch claims 7).
   - risks:
     - Incorrect prioritization if hidden issue is omitted.
   - validation:
     - Batch has 7 concrete IDs before execution handoff.

   **Root Cause Analysis**
   - Source summary count and explicit IDs do not align.

   **Implementation Strategy**
   1. Retrieve canonical issue table.
   2. Map missing ID to exact file/module.
   3. Insert final issue entry before coding starts.

   **Dependencies**
   - External documentation dependency.

   **Risk Assessment**
   - High coordination risk, low technical risk.

   **Verification Plan**
   - Sign-off checklist item confirming 7 resolved IDs.

   **Estimated Effort**
   - **Low**

### Dependencies
- Implement `H4` before broad pagination QA and before any list-performance tuning.
- `H6` should precede broad manual ownership/shared-edit test passes.
- `L2`/`L3` can run in parallel with `H4`/`H5`.
- `UNMAPPED-22A` must be resolved before marking batch complete.

### Rollback / Safety
- Ship one issue per commit for selective rollback.
- For `H4`, keep fallback behavior that prefers dropping suspicious page payloads over appending stale data.
- For `H6`, guard stricter validation behind explicit error messaging to avoid silent save failures.

### Acceptance Criteria
- [ ] H4: ALL-tab pagination never appends rows from stale filter/search/sort snapshots.
- [ ] H5: Day header amount rendering is locale-safe, currency-safe, and sign-accurate.
- [ ] H6: Ownership/shared/transfer edits validate consistently and persist atomically.
- [ ] M3: Transactions/Add Expense use lifecycle-aware collection for long-lived state flows.
- [ ] L2: No targeted hardcoded Add Expense UI literals remain.
- [ ] L3: Prefill is argument-stable and one-shot per input set.
- [ ] Batch 22 count mismatch resolved with canonical 7th issue ID.
