# Orchestrator Prompt v2 — Safe Engine Fix Workflow With Cost-Aware Agent Routing

Repo: `https://github.com/panospao7/Cost-agregator`  
Target branch: `{TARGET_BRANCH}`  
Engine: `{ENGINE_NAME}`  
Engine issue / slice: `{ENGINE_ISSUE_OR_PR_SLICE}`  
Input docs/plans: `{PASTE_AUDIT_AND_IMPLEMENTATION_PLAN}`

---

# Mission

Fix `{ENGINE_ISSUE_OR_PR_SLICE}` for `{ENGINE_NAME}` without regressing pipelines.

This is **engine-focused**, but engines are shared contracts. Every fix must protect affected pipelines through:

1. scout/re-audit
2. scoped planning
3. minimal implementation
4. static debugging
5. adversarial review
6. static test review
7. docs/tracker update
8. final compile/test only after all engine PRs are finalized

Do **not** trust old tracker statuses. Verify current code.

---

# Agent routing / model policy

## Default low-cost loop

Use this for most slices:

```text
orchestrator: Kimi K2.6
scout: MiniMax M3 or DeepSeek V4 Flash
planner: Kimi K2.6 or DeepSeek V4 Pro
coder: DeepSeek V4 Flash
debugger: DeepSeek V4 Pro
tester: Qwen3.6 Plus
reviewer: Qwen3.6 Plus or DeepSeek V4 Pro
documentor: DeepSeek V4 Flash
```

## Advanced agents

Use advanced agents sparingly.

```text
planner_advanced: GLM 5.1
reviewer_advanced: Qwen3.7 Max
```

### Use `planner_advanced` / GLM 5.1 only for:

```text
- complex architecture planning
- UI architecture affecting multiple screens
- schema/migration design
- Hilt/DI redesign
- broad cross-engine contract changes
- MoneyAmount/CurrencyConverter/TimePeriodUtils strategy
- PRs where normal planner reports yellow/red uncertainty
```

### Use `reviewer_advanced` / Qwen3.7 Max only for:

```text
- final pre-merge gate for an engine
- security/privacy/cloud review
- Room migration review
- money/currency correctness review
- high-risk engine contract changes
- broad pipeline-regression review
- when normal reviewer/debugger disagree
```

## Cost rule

Do not use GLM 5.1 or Qwen3.7 Max for routine edits, docs, simple refactors, or first-pass reviews.

Default:

```text
Kimi orchestrator
DeepSeek Flash coder/documentor
DeepSeek Pro debugger
Qwen Plus tester/reviewer
```

Escalate only when risk justifies it.

---

# Critical execution rules

## During each individual PR/slice

Do **static inspection/reasoning only**.

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

Allowed:

```text
grep/search
static source inspection
static compile reasoning
code edits
test authoring
docs updates
debugger static pass
reviewer static pass
tester static pass
```

## Only after all planned PRs for the engine are finalized

Then and only then run full validation:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema/migration changed:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changed:

```bash
./gradlew :app:assembleDebug --stacktrace
```

Do not claim final green until validation passes.

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
- combine schema migration + broad engine rewrite
- change global CurrencyConverter semantics casually
- change MoneyAmount representation casually
```

Stop and ask human before:

```text
- MoneyAmount representation changes
- CurrencyConverter public behavior changes
- TimePeriodUtils global behavior changes
- ExpenseDao aggregate-query changes
- destructive migrations
- broad Hilt rewiring
- privacy/cloud policy changes
- behavior outside documented issue scope
```

---

# Required docs to read first

Scout must read:

```text
docs/architecture/ENGINE_INTERACTION_MAP.md
docs/architecture/CODEBASE_SEGMENTS.md
docs/analyses and debug master/ENGINE_ISSUES_MASTER_TRACKER.md
docs/debugging-slicing-and-checklist.md
docs/architecture/**
relevant engine audit/debug report
provided implementation plan
```

---

# Required workflow per PR/slice

Every slice must follow this exact routine:

```text
1. scout
2. planner
3. coder
4. debugger
5. coder fixes debugger findings
6. debugger again
7. reviewer
8. coder fixes reviewer findings
9. reviewer again
10. tester static review
11. coder fixes test coverage issues
12. debugger + reviewer final static pass
13. slice completion report
```

If debugger or reviewer reports red:

```text
do not proceed
fix
repeat debugger/reviewer loop
```

If both are yellow:

```text
document why yellow is acceptable
document deferred risk
continue only if human-approved or clearly low-risk
```

Use `planner_advanced` if the planner cannot produce a safe slice.  
Use `reviewer_advanced` before merging high-risk or final engine work.

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

Do not code until reconciliation is complete.

---

# Phase 3 — Pipeline impact contract

For every intended change define:

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
- no misleading broad result is returned for unsupported query
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
Escalation needed? yes/no
Final validation commands:
```

Use `planner_advanced` only if:

```text
- schema or migration is involved
- multiple pipelines are affected
- high-risk money/time/privacy contracts change
- default planner reports uncertainty
```

---

# Phase 5 — Code

Coder must:

1. Implement minimal fix.
2. Add engine unit tests.
3. Add affected pipeline/use-case tests.
4. Update docs/tracker.
5. Avoid unrelated refactors.
6. Avoid migrations unless explicitly part of slice.

---

# Phase 6 — Static debugger loop

Debugger checks:

```text
- compile risk by reading signatures/imports
- constructor/call-site breakage
- Hilt injection consequences
- Room entity/DAO/migration consistency
- nullable/non-null mismatch
- CancellationException handling
- TimeProvider usage
- write barrier usage
- privacy/redaction ordering
- transaction boundaries
- side-effect timing
- money/currency validation
- tests assert actual bug
```

Debugger output:

```text
Debugger verdict: green/yellow/red
Issues found:
Required fixes:
Risk if ignored:
```

Repeat until green/yellow-acceptable.

---

# Phase 7 — Reviewer loop

Reviewer checks:

```text
1. Does fix close issue?
2. Does it preserve affected pipeline behavior?
3. Are invalid inputs rejected, not coerced?
4. Are privacy gates/redactors enforced?
5. Are money/currency warnings preserved?
6. Are timestamps deterministic?
7. Are restore writes blocked?
8. Are mutations/events atomic where required?
9. Are side effects post-commit?
10. Are deprecated/raw paths blocked or documented?
11. Are docs honest?
12. Are tests strong?
```

Reviewer output:

```text
Reviewer verdict: green/yellow/red
Regression risks:
Required fixes:
Deferred items:
```

Use `reviewer_advanced` when:

```text
- final engine gate
- privacy/cloud/security change
- migration/schema change
- foundational money/time change
- normal reviewer gives yellow/red on architecture
```

---

# Phase 8 — Static tester pass

Tester must not run tests during slices.

Tester checks:

```text
- engine tests cover old bug
- pipeline tests cover affected consumers
- invalid/failure/no-op paths tested
- privacy tests include redacted/denied modes
- money tests include invalid currency/non-finite amount
- time tests use fixed TimeProvider
- no @Ignore
- no weak only-not-null assertions
- test names describe behavior
```

---

# Phase 9 — Slice completion report

For every slice, output:

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
Debugger verdict:
Reviewer verdict:
Tester verdict:
Advanced agents used? why/why not:
Known compile risks:
Human validation commands:
Follow-up/deferred items:
```

---

# Final engine validation

After all engine PRs are finalized:

1. Run full validation commands.
2. Use `reviewer_advanced` as final pre-merge gate.
3. Produce final report:

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

# Final instruction

Be conservative. Engine fixes may change pipeline behavior only when old behavior was unsafe, misleading, or invalid.

Every change must be:

```text
scouted -> planned -> coded -> statically debugged -> fixed -> reviewed -> fixed -> statically test-reviewed -> documented
```

No slice is complete until debugger, reviewer, and tester agree it is clean or explicitly yellow-acceptable with documented follow-up.