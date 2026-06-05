# Final Gate Prompt — Engine Fix Deep Debugging + Pipeline Regression Review

Repo: `https://github.com/panospao7/Cost-agregator`  
Target branch/commit: `{TARGET_BRANCH_OR_COMMIT}`  
Engine reviewed: `{ENGINE_NAME}`  
Engine fix PRs/commits: `{LIST_FIX_PRS_OR_COMMITS}`  
Original audit: `{PASTE_OR_LINK_ENGINE_AUDIT}`  
Implementation plan: `{PASTE_OR_LINK_ENGINE_IMPLEMENTATION_PLAN}`  
Non-regression checklist: `{PASTE_OR_LINK_CHECKLIST}`

---

# Mission

Perform a **deep final-gate review** after all fixes for `{ENGINE_NAME}` are implemented.

You are not implementing new features unless a blocking issue is found. Your job is to prove whether the engine is truly fixed and whether affected pipelines are not regressed.

Review both:

```text
1. Engine correctness
2. Pipeline behavior/regression risk
```

Do not trust commit messages, tracker status, or prior agent claims. Verify current code.

---

# Tool/model routing

Use strong review/debug tools.

Recommended:

```text
scout: MiniMax M3 or DeepSeek Flash
debugger: DeepSeek V4 Pro
tester: Qwen3.6 Plus
reviewer: Qwen3.6 Plus
reviewer_advanced/final gate: Qwen3.7 Max
documentor: DeepSeek Flash
```

Use `reviewer_advanced` for final verdict if:

```text
- engine is foundational
- privacy/cloud/security involved
- money/currency/time involved
- Room/Hilt/migration involved
- pipelines are broad
- normal reviewer finds yellow/red uncertainty
```

---

# Critical rules

During final-gate review, you may run full validation **only if all engine PRs are finalized**.

Allowed final validation commands:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If Room/schema/migration changed:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changed:

```bash
./gradlew :app:assembleDebug --stacktrace
```

Never use:

```text
fallbackToDestructiveMigration
clear app data
rerun financial rescue
```

Do not declare green if compile/tests fail.

If validation cannot be run, clearly state:

```text
Final verdict is static-only, not validated by build/tests.
```

---

# Required documents to read

Read first:

```text
docs/architecture/ENGINE_INTERACTION_MAP.md
docs/architecture/CODEBASE_SEGMENTS.md
docs/analyses and debug master/ENGINE_ISSUES_MASTER_TRACKER.md
docs/debugging-slicing-and-checklist.md
the original engine audit
the engine implementation plan
the engine non-regression checklist
all docs updated by the fix PRs
```

Also inspect commit diffs for all listed PRs/commits.

---

# Phase 1 — Fix inventory

Create a complete inventory:

```text
Engine:
Target branch/commit:
Fix PRs/commits:
Files changed:
Tests changed:
Docs changed:
Schema/migration changed? yes/no:
Hilt/DI changed? yes/no:
Privacy/cloud changed? yes/no:
Money/currency/time changed? yes/no:
Affected pipelines:
```

For each commit/PR:

```text
Commit/PR:
Claimed issue fixed:
Actual files changed:
Risk level:
Affected pipelines:
```

---

# Phase 2 — Reconcile every original issue

For every issue in the original audit/plan:

```text
Issue ID:
Original status:
Planned fix:
Current code evidence:
Current test evidence:
Pipeline evidence:
Status now:
  - fixed
  - mostly fixed / caveat
  - partial
  - still open
  - regressed
Decision:
Follow-up needed:
```

Important:

```text
Do not mark fixed unless both code and tests support it.
If code is fixed but tests are missing, mark "mostly fixed / needs tests".
If pipeline impact is untested, mark "yellow".
```

---

# Phase 3 — Diff-focused engine review

Review the actual diff for engine correctness.

Check:

```text
- Did implementation match the plan?
- Did it solve the real bug or only symptoms?
- Did it preserve public contracts where required?
- Did it introduce incompatible signature changes?
- Did it add new direct DAO writes?
- Did it add new raw Double/mixed-currency paths?
- Did it add new direct System.currentTimeMillis/Calendar/now calls?
- Did it weaken privacy/redaction?
- Did it hide data-quality warnings?
- Did it add broad catch(Exception) without CancellationException rethrow?
- Did it dispatch side effects before DB commit?
- Did it skip write barrier on new writes?
- Did it silently fallback to EUR/defaults?
- Did it add schema changes without migration/tests?
```

Output:

```text
Engine diff verdict:
Blocking issues:
Non-blocking issues:
Risk notes:
```

---

# Phase 4 — Pipeline call-site review

Using `ENGINE_INTERACTION_MAP.md`, list every affected pipeline and inspect relevant call sites.

For each pipeline:

```text
Pipeline:
Engine call sites:
Expected behavior after fix:
Static evidence:
Tests covering it:
Regression risks:
Verdict:
  - green
  - yellow
  - red
```

Minimum pipeline checks by engine:

## Engine 1 — Warranty / Subscription / Location / NLP

Check:

```text
receipt warranty flow
manual warranty flow
subscription creation/candidate/price change
recurring/budget/forecast subscription consumers
cloud query privacy/redaction
assistant query execution
map/location GPS/privacy
NLP location/amount/merchant queries
backup/restore write barrier
```

## Engine 2 — Analytical Engines

Check:

```text
analytics ViewModel
dashboard totals
budget-vs-actual
daily/weekly/monthly charts
advanced analytics
insights
spending personality
location analytics
currency/data-quality warnings
```

## Engine 3 — Categorization / Merchant Normalization

Check:

```text
notification categorization
pending review approval/rejection
receipt merchant matching
email ingestion categorization
recurring merchant matching
analytics merchant/category grouping
transaction side-effect learning
cache invalidation
backup/restore write barrier
privacy/debug traces
```

## Engine 4 — Groups / Investment / Tax

Check:

```text
group create/add/remove/archive/delete
group expense link/system expense link
settlements/balances
shared budget offsets
investment add/update/summary/performance/history
tax estimate/year summary
business reports/CSV export
backup/restore write barrier
```

## Engine 5 — Money / Time Primitives

Check:

```text
analytics date ranges
dashboard period widgets
budget period calculations
exports/accounting formatting
currency normalization aggregates
tax/group/investment aggregate consumers
restore journal TimeProvider
week helpers
legacy PeriodRange imports
```

---

# Phase 5 — Test quality review

Inspect added/updated tests.

For every changed test file:

```text
Test file:
What issue it covers:
Engine coverage:
Pipeline coverage:
Negative cases:
Partial/failure cases:
Time/currency/privacy cases:
Weak assertions? yes/no:
Uses fixed TimeProvider? yes/no:
Uses realistic pipeline input? yes/no:
Verdict:
```

Reject weak coverage if tests only assert:

```text
not null
list not empty
method does not throw
```

unless that is exactly the bug.

Required test expectations:

```text
- invalid/failure path tested
- valid old flow still works
- pipeline consumer covered
- no @Ignore
- deterministic time
- mixed currency when money involved
- privacy denied/redacted when privacy involved
- post-commit behavior when lifecycle involved
```

---

# Phase 6 — Static guards and regression traps

Check for guardrails relevant to this engine:

```text
direct wall-clock guard
raw DAO mutator guard
raw mixed-currency API guard
default-EUR formatter guard
legacy overload guard
legacy PeriodRange import guard
cloud without redaction guard
side-effect-before-commit guard
write barrier guard
```

For each:

```text
Guard:
Exists? yes/no:
Effective? yes/no:
Missing allowlist risks:
Recommended follow-up:
```

---

# Phase 7 — Build/test validation

If engine PRs are finalized, run:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If migration/schema changed:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Capture:

```text
Command:
Result:
Failures:
Likely cause:
Blocking? yes/no:
```

If failures occur:

1. classify as engine regression, test issue, unrelated existing issue, or environment issue
2. propose minimal fix
3. do not claim green

---

# Phase 8 — Adversarial final review

Use `reviewer_advanced` if available.

Ask:

```text
1. Could this fix silently change user financial results?
2. Could any pipeline now get stale/wrong data?
3. Could invalid dirty DB data crash now?
4. Could data be lost or overwritten?
5. Could privacy-sensitive text leave the app?
6. Could cloud calls happen without effective policy?
7. Could writes happen during restore/backup?
8. Could side effects fire before DB commit?
9. Could analytics/budget/export now disagree?
10. Could tests pass while real pipeline is broken?
11. Did any old raw/deprecated path remain production-callable?
12. Did docs overstate fixed status?
```

Output:

```text
Advanced reviewer verdict:
Blocking concerns:
Yellow concerns:
Accepted deferrals:
```

---

# Phase 9 — Final report

Produce this exact final structure:

```text
# Final Gate Review — {ENGINE_NAME}

## 1. Verdict
GREEN / YELLOW / RED

## 2. Validation status
Compile:
Unit tests:
Check/lint:
Connected tests if needed:

## 3. Issue reconciliation summary
Fixed:
Mostly fixed / needs tests:
Partial:
Still open:
Regressed:

## 4. Engine correctness review
What is correct:
What is risky:
Blocking issues:

## 5. Pipeline regression review
Pipeline table with green/yellow/red status.

## 6. Tests review
Strong tests:
Weak/missing tests:
Required additions:

## 7. Docs/tracker review
Docs updated:
Overstated statuses:
Needed tracker changes:

## 8. Guardrails review
Existing guards:
Missing guards:

## 9. Files/commits reviewed
List.

## 10. Required fixes before merge
List blocking fixes.

## 11. Follow-up/deferred work
List non-blocking items.

## 12. Final recommendation
Merge / do not merge / merge only after listed fixes.
```

---

# Verdict rules

## GREEN

Allowed only if:

```text
- code fixes match plan
- affected pipeline call sites reviewed
- tests cover engine and pipeline behavior
- compile/tests/check pass
- no blocking privacy/money/time/schema issues
- docs/tracker are honest
```

## YELLOW

Use if:

```text
- core fix works
- some tests/guards are missing
- risk is documented and acceptable
- no known severe regression
```

Do not merge yellow unless human accepts follow-up.

## RED

Use if:

```text
- compile/test fails due fix
- engine issue not actually fixed
- affected pipeline regressed
- privacy weakened
- money/currency/time correctness broken
- migration/schema incomplete
- data-loss risk exists
```

---

# Final instruction

Be stricter than the implementation agents.

This is the final gate. Your job is not to be optimistic; your job is to prevent regressions.

Do not mark an engine clean because code “looks better.”  
Mark it clean only when the fixes are verified across engine contracts, affected pipelines, tests, docs, and final validation.