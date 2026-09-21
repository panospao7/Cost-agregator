# Orchestrator Prompt — Safe Engine Issue Fix Workflow

Repo: `https://github.com/panospao7/Cost-agregator`  
Target branch: `{TARGET_BRANCH}`  
Engine: `{ENGINE_NAME}`  
Engine issue / slice: `{ENGINE_ISSUE_OR_PR_SLICE}`  
Input docs/plans: `{PASTE_AUDIT_AND_IMPLEMENTATION_PLAN}`

## Core mission

Fix `{ENGINE_ISSUE_OR_PR_SLICE}` for `{ENGINE_NAME}` without regressing pipelines.

This is **engine-focused**, but engines are shared contracts. Every fix must protect affected pipelines through impact analysis, tests, static debugging, and adversarial review.

Do **not** trust old tracker statuses. Verify current code.

---

# Tool roles

Use the tools in this order:

1. `scout` — inspect code/docs/call sites.
2. `planner` — produce safe scoped implementation plan.
3. `coder` — implement minimal fix and tests.
4. `debugger` — static debugging only; find code/test/design issues.
5. `reviewer` — adversarial review against engine + pipeline contracts.
6. `tester` — author/review tests statically; final full compile/test only after all engine PRs are complete.

After every PR/slice:

```text
coder -> debugger -> fix -> debugger again
      -> reviewer -> fix -> reviewer again
      -> tester static review -> fix if needed
      -> final static clean verdict for slice
```

Repeat until debugger and reviewer both report clean/yellow-acceptable.

---

# Critical execution rules

## During individual PR/slice work

Do **not** run Gradle/compile/tests/lint/KSP/Hilt/Room validation.

Forbidden during slice work:

```bash
./gradlew
gradle
assembleDebug
testDebugUnitTest
check
lint
connectedDebugAndroidTest
compileDebugKotlin
kapt
ksp
```

Allowed during slice work:

```text
grep/search
static source inspection
code edits
test authoring
docs updates
static compile reasoning
static debugger/reviewer/tester passes
```

## Only after all planned PRs for the engine are finalized

Then and only then run full validation:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If Room/schema changed:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changed:

```bash
./gradlew :app:assembleDebug --stacktrace
```

Do not claim final green until these pass.

---

# Safety constraints

Do not:

```text
- use fallbackToDestructiveMigration
- clear app data
- rerun financial rescue
- weaken privacy/redaction policy
- remove data-quality warnings
- silently raw-sum mixed currencies
- dispatch side effects before DB commit
- introduce direct wall-clock calls where TimeProvider exists
- add broad Hilt rewiring in a small fix slice
- combine migration + major logic rewrite unless explicitly approved
```

Stop and ask human before:

```text
- touching MoneyAmount representation
- changing CurrencyConverter semantics
- changing TimePeriodUtils global behavior
- changing ExpenseDao aggregate queries
- adding/destructive Room migrations
- broad Hilt module rewiring
- changing privacy/cloud policy
- changing behavior outside the issue scope
```

---

# Required docs to read first

The scout must read:

```text
docs/architecture/ENGINE_INTERACTION_MAP.md
docs/architecture/CODEBASE_SEGMENTS.md
docs/analyses and debug master/ENGINE_ISSUES_MASTER_TRACKER.md
docs/debugging-slicing-and-checklist.md
docs/architecture/**
relevant engine audit/debug reports
the provided implementation plan
```

For Engine 1 specifically, inspect:

```text
WarrantyTrackerRepository.kt
WarrantyDao.kt
WarrantyLifecycleEvent.kt
SubscriptionManagerEngine.kt
SmartBillNegotiationEngine.kt
MarketRateProvider.kt
LocationResolver.kt
SpendingMapViewModel.kt
NaturalLanguageSearchEngine.kt
NaturalLanguageExpenseQueryRepositoryImpl.kt
ExecuteFinancialQueryUseCase.kt
CloudQueryInterpretationService.kt
ScannedReceipt.kt
ReceiptDocumentType.kt
ReceiptSourceType.kt
ReceiptProcessingStatus.kt
```

---

# Phase 1 — Scout report

Before editing, produce:

```text
Engine:
Issue/slice:
Risk level:
Old tracker IDs:
Current verified status:
Files inspected:
Call sites:
Affected pipelines:
Affected tests:
Shared dependencies:
Schema/migration impact:
Hilt/DI impact:
Privacy impact:
Money/currency impact:
Time/lifecycle impact:
Docs needing update:
```

Use grep/search for call sites.

Examples:

```bash
grep -R "SubscriptionManagerEngine" app/src/main/java app/src/test
grep -R "WarrantyTrackerRepository" app/src/main/java app/src/test
grep -R "CloudQueryInterpretationService" app/src/main/java app/src/test
grep -R "SmartBillNegotiationEngine" app/src/main/java app/src/test
```

---

# Phase 2 — Reconcile old issue

For every related old issue:

```text
ID:
Old tracker status:
Current verified status:
Evidence:
Affected pipelines:
Tests proving current behavior:
Missing tests:
Regression risk:
Decision:
  - keep fixed
  - mostly fixed / needs tests
  - downgrade to partial
  - reopen
  - deferred by design
```

Do not proceed to coding until reconciliation is done.

---

# Phase 3 — Pipeline impact contract

For every intended change, define:

```text
Engine contract before:
Engine contract after:
Affected pipelines:
Expected behavior change:
Non-regression criteria:
Pipeline tests needed:
Compatibility adapter needed? yes/no
```

A pipeline is not regressed if:

```text
- valid old flow still works
- invalid input is rejected clearly
- no user data loss
- no silent EUR/default fallback without warning
- no cloud call without privacy gate + redaction
- no write during restore/backup blocked mode
- no side effect before transaction commit
- UI receives warning/unsupported state instead of misleading broad result
```

---

# Phase 4 — Plan safe slice

Planner must output:

```text
Slice ID:
Issues closed:
Issues partially improved:
Files to change:
Files not to touch:
Risk:
Schema impact:
Hilt impact:
Implementation steps:
Engine tests:
Pipeline regression tests:
Docs updates:
Debugger focus:
Reviewer focus:
Final validation commands:
```

Rules:

```text
- one high-risk concept per slice
- no migration in no-schema slices
- no broad refactor
- preserve public contracts unless deliberately migrating
- if contract changes, update all affected adapters/callers
```

---

# Phase 5 — Code

Coder must:

1. Implement minimal fix.
2. Add engine unit tests.
3. Add affected pipeline/use-case tests.
4. Update docs/tracker.
5. Avoid unrelated formatting/refactors.
6. Avoid changing migrations unless this slice explicitly requires it.

For Engine 1 recommended slice order:

```text
PR1: no-schema hardening
  - warranty timestamp normalization
  - manual warranty placeholder metadata
  - subscription finite/currency validation
  - cloud CancellationException rethrow

PR2: cloud privacy constructor hardening

PR3: warranty lifecycle events

PR4: low-confidence warranty review routing

PR5: NLP location query semantics

PR6: bill negotiation provider wiring

PR7: bill negotiation monthly-equivalent script fix

PR8: bill negotiation persistence, migration only if approved

PR9: deprecated/raw API guardrails
```

---

# Phase 6 — Static debugger loop

Debugger must inspect the diff without running Gradle.

Check:

```text
- compile-risk by reading imports/constructors/signatures
- missing imports
- changed constructor call sites
- Hilt injection consequences
- Room entity/DAO/migration consistency if touched
- nullable/nonnull mismatches
- coroutine CancellationException handling
- TimeProvider usage
- write barrier usage
- privacy/redaction ordering
- transaction boundaries
- side-effect timing
- money/currency validation
- tests actually assert the bug
```

Debugger output:

```text
Debugger verdict: green/yellow/red
Issues found:
Required fixes:
Risk if ignored:
```

If yellow/red, coder fixes, then debugger repeats.

---

# Phase 7 — Adversarial reviewer loop

Reviewer must challenge the fix.

Checklist:

```text
1. Does this actually close the engine issue?
2. Does it preserve affected pipeline behavior?
3. Are invalid inputs rejected instead of silently coerced?
4. Are privacy gates/redactors enforced before cloud/debug/export?
5. Are money/currency warnings preserved?
6. Are timestamps deterministic through TimeProvider?
7. Are DB writes blocked during restore if relevant?
8. Are mutations/events atomic where required?
9. Are side effects post-commit?
10. Are deprecated/raw paths not reintroduced?
11. Are docs/tracker honest?
12. Are tests strong, not superficial?
```

Reviewer output:

```text
Reviewer verdict: green/yellow/red
Regression risks:
Required fixes:
Deferred items:
```

If yellow/red, coder fixes, then reviewer repeats.

---

# Phase 8 — Static tester pass

Tester must not run tests during slices.

Tester checks:

```text
- engine tests cover old bug
- pipeline tests cover affected consumers
- invalid/failure/no-op paths tested
- privacy tests include redacted/denied mode
- money tests include invalid currency/non-finite amount where relevant
- tests use fixed TimeProvider where relevant
- no @Ignore
- no weak assertions like only-not-null
- test names describe behavior
```

Tester output:

```text
Static tester verdict:
Tests added:
Missing test coverage:
Required fixes:
```

If missing coverage, coder adds tests and debugger/reviewer repeat.

---

# Phase 9 — Slice completion report

For each PR/slice, produce:

```text
Slice:
Self-review verdict: green/yellow/red
Old issues reconciled:
New issues found:
Files changed:
Tests added/updated:
Docs updated:
Affected pipelines:
Expected behavior changes:
Static debugger verdict:
Reviewer verdict:
Tester static verdict:
Known compile risks:
Human validation commands:
Follow-up/deferred items:
```

Do not claim full engine clean until all planned slices are done and final compile/tests pass.

---

# Final engine validation

After all engine PRs are finalized, now tester may run:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If migration/schema changed:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Final output must include:

```text
Final engine verdict:
Compile result:
Unit test result:
Check/lint result:
Connected test result if run:
Remaining risks:
Deferred issues:
Pipeline regression status:
Recommended next engine/slice:
```

---

# Engine 1 specific non-regression checklist

For Warranty / Subscription / Location / NLP, confirm:

```text
Warranty:
- manual warranty creation still works
- AI/receipt warranty creation still works
- warranties have nonzero timestamps
- manual placeholder stores no raw product name in rawOcrText
- manual placeholder has MANUAL_PLACEHOLDER document type
- protected-value aggregate still works

Subscription:
- valid subscription creation still works
- valid candidate acceptance still works
- valid price change still works
- invalid NaN/Infinity/123 currency is rejected
- monthly subscription aggregate still works
- recurring/budget/forecast/dashboard consumers still see valid subscriptions

Cloud/AI:
- privacy denied does not call HTTP
- redaction occurs before prompt body creation
- CancellationException is rethrown
- assistant largest/total/count still works

Location/NLP:
- GPS only fetched through privacy gate and explicit action
- spending heatmap remains spending-only
- currency conversion warnings remain visible
- location-specific NLP query is filtered or explicitly unsupported
- merchant/date/amount NLP queries still work
```

---

# Final instruction

Be conservative. Engine fixes are allowed to change pipeline behavior only when the old behavior was unsafe, misleading, or invalid.

Every change must be:

```text
scouted -> planned -> coded -> statically debugged -> fixed -> reviewed -> fixed -> statically test-reviewed -> documented
```

No slice is complete until debugger, reviewer, and tester all agree it is clean or explicitly yellow-acceptable with documented follow-up.