# PR B — Remove Unsafe Exemptions

## 1. PR definition

**Suggested title:**  
`ci: replace broad guard exemptions with exact enforceable boundaries`

**Base:** Successful final commit of PR A.

**Reference snapshot:**  
`ebb5aa93348282b31c1c669d1bf1271d584b9eb0`

**Primary issues:**

- MIT-003 — Missing/weak architecture guards
- MIT-005 — Ignored critical-test debt
- MIT-016 — Worker full-guard enforcement
- MIT-028 — Release security hardening
- MIT-034 — Cancellation propagation
- MIT-036 — DAO ownership policy
- MIT-060 — UI DAO ownership, verification only after PR A

**Estimated effort:** 4–7 engineering days.

## 2. Objective

Make exemptions incapable of hiding an entire file, class, worker, or category of future violations.

At completion:

1. No production worker is exempt from the full worker guard.
2. Worker exceptions, if absolutely necessary, suppress one exact subrule at one exact symbol.
3. `app/build.gradle.kts` is always scanned completely.
4. A minification exception cannot hide `isDebuggable=true` or future release defects.
5. `MoneyTest` is restored as release-critical and contains no ignored tests.
6. Wildcard and permanent debt exemptions fail allowlist compliance.
7. Every exception is exact, expiring, issue-linked, owner-reviewed, and backed by a test.
8. Stale and overbroad allowlist entries fail CI.

The current worker guard returns immediately for an allowlisted worker, bypassing every check in that class. The DI guard similarly returns before scanning an allowlisted Gradle file. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_worker_boundaries.py))

---

# 3. Non-goals

Do not include:

- Warning-backlog baseline enforcement; that belongs to PR C.
- Full release artifact security verification; that belongs to the release-verification PR.
- Full cancellation backlog burn-down.
- Migration execution changes.
- Unrelated worker behavior changes.
- New OAuth/banking functionality.
- Global migration of every historical allowlist format.
- New permanent exceptions to restore green CI.

Do not:

- Replace a broad exemption with another wildcard.
- Move an exemption into source comments.
- Add `@Ignore`.
- Remove another class from the release-critical denylist.
- Mark unresolved architectural debt as permanent.
- weaken a detector to accommodate existing production code.

---

# 4. Workstream B1 — Establish an exact exemption model

## 4.1 Split findings into stable subrules

Replace monolithic matching where practical with explicit subrules.

### Worker subrules

- `G-WORKER-01A` — missing `WorkerExecutionGuard`
- `G-WORKER-01B` — direct DAO mutation
- `G-WORKER-01C` — missing guard-result bridge
- `G-WORKER-01D` — cancellation swallowed
- `G-WORKER-01E` — broad catch without structured diagnostic
- `G-WORKER-01F` — DB access outside guarded execution scope

No exemption may suppress `G-WORKER-01A` for a DB-reading or DB-writing production worker.

### DI/release subrules

- `G-DI-01A` — debug/mock/fake binding reachable in release
- `G-DI-01B` — unsafe or cleartext endpoint
- `G-DI-01C` — release minification disabled
- `G-DI-01D` — release build debuggable
- `G-DI-01E` — sensitive request/body logging
- `G-DI-01F` — no-op or stub production binding

This allows an exact temporary decision on minification without suppressing unrelated release checks.

## 4.2 New allowlist schema

High-risk allowlists must identify:

- Exact rule/subrule.
- Exact repository-relative path.
- Exact class and symbol.
- Exact violation category.
- Technical reason.
- Safety invariant that remains enforced.
- Owner.
- ISO expiry date.
- Linked issue.
- Evidence test.
- Exception classification: `temporary_debt` or `structural_exception`.

### Temporary debt

Must:

- Have an expiry no more than 90 days after introduction.
- Link to an open corrective issue.
- Name the expected removal condition.
- Never use `permanent`.
- Never use `symbol: "*"`.

### Structural exception

Allowed only when the behavior is intrinsically required, such as Room migration SQL.

Must:

- Explain why ordinary architecture cannot apply.
- Identify compensating controls.
- Reference a test proving those controls.
- Be explicitly approved for that guard/subrule.

“Pre-existing,” “reviewed,” “legacy,” or “intentional” is not sufficient justification.

## 4.3 Exact matching rules

Replace:

- Basename matching.
- Bidirectional suffix matching.
- Whole-file matching.
- Whole-class early returns.
- Empty-symbol wildcard behavior.

With:

- Normalized POSIX repository-relative paths.
- Exact rule match.
- Exact path match.
- Exact symbol match.
- Exact violation category match.

Detection must happen before allowlist filtering.

Processing order:

1. Scan the complete file.
2. Generate all raw findings.
3. Assign stable fingerprints.
4. Match each finding against at most one exact exception.
5. Report suppressed findings separately.
6. Fail on unmatched blocking findings.
7. Fail on stale or overbroad exceptions.

## 4.4 Stable finding fingerprint

Each finding should include:

- Rule ID.
- Normalized path.
- Class.
- Symbol.
- Violation type.
- Normalized statement signature.

Do not fingerprint solely by line number because routine edits move lines.

## 4.5 Allowlist effectiveness validation

Extend `verify_allowlist_compliance.py` or add a companion meta-guard that fails when:

- An entry matches zero current findings.
- An entry matches multiple findings.
- Two entries match the same finding.
- A temporary entry has expired.
- A debt entry says `permanent`.
- A high-risk entry uses a wildcard.
- A path does not exist.
- A symbol does not exist.
- The linked issue is missing.
- The evidence test does not exist.
- A structural exception uses a rule not approved for structural exceptions.

The existing compliance guard validates reasons, owners and expiry, but currently treats empty parsed allowlists as a warning and does not prove that an exception corresponds to one live finding. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_allowlist_compliance.py))

## Tests

Add fixtures proving:

1. Exact exception suppresses exactly one finding.
2. Same-file second finding remains blocking.
3. Wildcard symbol fails compliance.
4. Basename-only path does not match.
5. Expired debt fails.
6. Permanent debt fails.
7. Stale exception fails.
8. One entry matching two findings fails.
9. Missing evidence test fails.
10. Malformed allowlist exits with code 2.
11. Missing required allowlist exits with code 2.
12. Empty valid allowlist passes without warning.

---

# 5. Workstream B2 — Remove all worker class exemptions

Six production workers were made permanently exempt:

- `LocationBackfillWorker`
- `MerchantKeyBackfillWorker`
- `DataRetentionWorker`
- `ReceiptMatchingWorker`
- `BillReminderWorker`
- `WarrantyExpirationWorker`

The exemptions currently suppress the entire worker scan rather than only the cited broad-catch or DAO finding. ([github.com](https://github.com/panospao7/Cost-agregator/commit/0bbb52e))

## 5.1 Establish the unsuppressed baseline

Before changing worker code:

1. Temporarily run the worker guard with an empty allowlist.
2. Export every raw finding.
3. Group findings by worker and subrule.
4. Record the result in the PR description.
5. Do not commit a generated baseline that permits those findings.

The previous report said 16 findings were hidden across the six workers. Recalculate after PR A because touched code may have changed.

## 5.2 Redesign the worker allowlist behavior

Remove `is_allowlisted_worker()` as a class-level bypass.

The worker guard must always verify:

- Guard entry exists.
- DB work occurs inside the guarded body.
- Result maps through `toWorkerResult()`.
- Cancellation propagates.
- Broad catches record typed diagnostics.
- DAO ownership is legal.

Delete the test asserting that an unguarded allowlisted worker passes. The current test explicitly treats this unsafe behavior as expected. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/test_verify_worker_boundaries.py))

Replace it with tests proving:

- Missing guard can never be exempted for DB workers.
- Direct DAO exemption does not suppress missing guard.
- Cancellation exemption does not suppress direct DAO access.
- Removal of `runGuarded` from an otherwise excepted worker fails.
- Adding a second DAO mutation creates a new failure.

## 5.3 Refactor worker ownership

### LocationBackfillWorker

Target:

`Worker → LocationBackfillService/Repository → DAO`

Tasks:

- Move batch query/mutation logic into a dedicated backfill owner.
- Keep the worker responsible only for request construction and result mapping.
- Execute the service inside `runGuardedWithContext`.
- Checkpoint between batches.
- Propagate cancellation.
- Record only sanitized counts and reason codes.

### MerchantKeyBackfillWorker

Target:

`Worker → MerchantKeyBackfillService → DAO`

Tasks:

- Move merchant-key mutation into the service.
- Ensure key derivation and row update form one resumable batch.
- Persist/checkpoint the last processed key or row where supported.
- Do not log merchant names or generated keys.
- Use the guard result bridge.

### DataRetentionWorker

Target:

`Worker → RetentionCoordinator/RetentionRegistry → legal repositories`

Tasks:

- Remove direct DAO ownership from the worker.
- Ensure privacy policy is evaluated inside the execution guard.
- Make partial target failures explicit.
- Rethrow cancellation immediately.
- Return retry only for retryable incomplete cleanup.
- Record each target using a safe target code, never table contents.

### ReceiptMatchingWorker

Target:

`Worker → ReceiptMatchLifecycleService → receipt/event owners`

Tasks:

- Route matching mutations through the lifecycle service.
- Remove receipt/event DAO injection from the worker.
- Preserve idempotency and transaction ownership.
- Ensure cancellation cannot be converted into normal matching failure.
- Record safe aggregate counts only.

The worker exists under the service receipt-matching path and is currently one of the permanently exempt classes. ([github.com](https://github.com/panospao7/Cost-agregator/tree/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/service/receiptmatching))

### BillReminderWorker

Target:

`Worker → BillReminderManager/RecurringLifecycleCoordinator`

Tasks:

- Remove direct reminder-delivery mutation from the worker.
- Keep claim, delivery result and lifecycle event ownership in the coordinator.
- Verify notification permission before claim.
- Return retry for maintenance/permission states according to documented policy.
- Preserve `toWorkerResult()` mapping.
- Rethrow cancellation before any failure mapping.

The current repository contains a dedicated bill-reminder worker and associated action workers, so changes must not accidentally alter snooze/dismiss behavior. ([github.com](https://github.com/panospao7/Cost-agregator/tree/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/service/reminder))

### WarrantyExpirationWorker

Target:

`Worker → WarrantyLifecycleCoordinator/Repository`

Tasks:

- Move delivery claim and lifecycle mutation out of the worker.
- Make claim idempotent.
- Ensure claim and terminal state follow legal transaction ownership.
- Run all DB work inside the guard.
- Return typed retry/failure outcomes.
- Do not log warranty descriptions, merchant details or receipt identifiers.

The DB audit specifically identified `deliveryDao.claim` in this worker, while the broad worker exemption currently hides every worker subrule. ([github.com](https://github.com/panospao7/Cost-agregator/commit/ebb5aa93348282b31c1c669d1bf1271d584b9eb0))

## 5.4 Broad-catch remediation

For every affected worker:

- Catch `CancellationException` first and rethrow.
- Catch only expected domain exceptions where possible.
- Convert retryable failures to typed outcomes.
- Use safe diagnostic reason codes.
- Do not pass raw Throwables to production logs.
- Do not treat an outer `WorkerExecutionGuard` as protection after inner cancellation has already been swallowed.

## 5.5 Final worker allowlist state

Preferred result:

- `worker_allowlist.yml` contains zero production worker entries.

If one direct-mutation exception is intrinsically unavoidable:

- It may suppress only `G-WORKER-01B`.
- It must name the exact method.
- Guard presence, execution scope, cancellation and result checks remain mandatory.
- It must expire unless classified and approved as structural.

## Worker tests

For each of the six workers:

- Guard invoked exactly once.
- Correct `BlockedPolicy`.
- DB operation does not execute when blocked.
- Cancellation propagates.
- Result bridge maps success/retry/failure correctly.
- No DAO is injected into the worker.
- No DB operation occurs before guard entry.
- No raw Throwable or sensitive value is logged.
- Existing scheduling identity remains unchanged.
- Existing idempotency behavior remains unchanged.

---

# 6. Workstream B3 — Remove the Gradle whole-file exemption

The current DI allowlist uses:

- Path: `app/build.gradle.kts`
- Symbol: `*`
- Expiry: `permanent`

The DI scanner then skips the file entirely. This can hide both disabled minification and a future `isDebuggable=true` setting. ([github.com](https://github.com/panospao7/Cost-agregator/commit/0bbb52e))

## 6.1 Remove whole-file skipping

Delete the early return from `scan_gradle_file()`.

Always scan every release property.

Apply exceptions only after each finding has been created.

## 6.2 Preferred minification resolution

Preferred implementation:

- Set release `isMinifyEnabled` to true.
- Confirm the optimized default ProGuard configuration is used.
- Add only required keep rules.
- Compile the release variant.
- Run release lint.
- Run release unit tests that are variant-compatible.
- Smoke-test Hilt, Room, WorkManager, serialization and reflection-dependent code.
- Verify no debug/stub binding becomes reachable.

Do not enable shrinking and then suppress release compilation failures.

## 6.3 Controlled fallback

If enabling minification exceeds PR B scope, permit one temporary exact entry:

- Rule: minification-specific subrule only.
- Path: exact Gradle path.
- Symbol: exact release minification property.
- Linked issue: MIT-028.
- Expiry: no more than 30 days after merge.
- Safety invariant: release remains non-debuggable and all other release checks remain active.
- Evidence test: exact-allowlist isolation test.

This fallback must not suppress:

- `isDebuggable=true`
- Cleartext configuration
- Debug signing
- Stub bindings
- Body logging
- Future release rules

## DI guard tests

Add a Gradle fixture containing both:

- Disabled minification.
- Enabled debuggability.

Then prove:

- Exact minification exception suppresses only minification.
- Debuggability still fails.
- Wildcard exception fails compliance.
- Basename-only exception does not match.
- A future release property remains visible.
- Missing Gradle file is an infrastructure error.

---

# 7. Workstream B4 — Restore MoneyTest as release-critical

`MoneyTest` was removed from the release-block denylist, while other critical suites remain listed. ([github.com](https://github.com/panospao7/Cost-agregator/commit/0bbb52e))

## 7.1 Restore denylist membership

Add `MoneyTest` back with:

- Critical money/currency correctness reason.
- Owner.
- No exemption or expiry—the entry is a requirement, not debt.

## 7.2 Remove ignored money tests

Locate every ignored test in `MoneyTest`.

For Truth/value-class boxing incompatibilities:

- Compare the underlying stable scalar representation.
- Use Kotlin/JUnit assertions where Truth cannot correctly resolve the value class.
- For decimal values, compare normalized decimal values rather than `Double`.
- Use explicit scale/rounding expectations.
- Do not replace exact money assertions with wide floating-point tolerances.

## 7.3 Strengthen money coverage

Ensure active tests cover:

- Equality and inequality.
- Hash/equality contract.
- Currency mismatch.
- Addition/subtraction rules.
- Rounding.
- Negative and zero values.
- Large values.
- Scale normalization.
- Serialization or persistence conversion where applicable.

## 7.4 Prevent future denylist removal

Add a guard test asserting the minimum release-critical set includes:

- `MoneyTest`
- `TransactionLifecycleCoordinatorTest`
- `DatabaseMigrationTest`
- `WorkerExecutionGuardTest`
- `ExportImportRoundtripTest`
- `ReceiptMatchingWorkerTest`
- `RecurringExpenseEngineTest`

Removing a critical class should require changing the policy test and CODEOWNER-reviewed configuration, not merely deleting four YAML lines.

## Acceptance criteria

- `MoneyTest` is in the critical list.
- No test in `MoneyTest` is ignored.
- All money tests execute and pass.
- The ignored-test guard passes.
- A fixture removing `MoneyTest` fails policy validation.

---

# 8. Workstream B5 — Correct ownership and traceability

## Required issue mapping

- UI DAO boundary: MIT-060.
- Worker guard presence/lease: MIT-016.
- Worker cancellation: MIT-034.
- Worker DAO ownership: MIT-036.
- Bill reminder policy: MIT-042 and MIT-067 where applicable.
- Worker diagnostics: MIT-017 or MIT-070.
- Release minification/security: MIT-028.
- Ignored critical tests: MIT-005.
- Guard framework policy: MIT-003.

Remove generic MIT-003 references from domain-specific debt entries.

## Documentation updates

Update:

- `guard-framework.md`
- `CI_GUARDRAILS_BASELINE.md`
- `developer-quickstart.md`
- `local-ci.md`
- `GUARD_VIOLATION_AUDIT.md`
- `MASTER_ISSUE_TRACKER.md`

Document:

- Exact exception schema.
- Structural versus temporary exceptions.
- Prohibition on class/file bypasses.
- Prohibition on permanent debt.
- Stale-entry enforcement.
- Worker allowlist final state.
- Actual release-minification decision.
- Restored critical money tests.

---

# 9. Recommended commit sequence

## Commit B1

`ci: make allowlist matching exact and finding-scoped`

Contains:

- Subrule/fingerprint model.
- Exact path/symbol matching.
- No early returns.
- Compliance checks.
- Meta-guard tests.

## Commit B2

`refactor(workers): remove class-wide worker guard exemptions`

Contains:

- Six worker refactors.
- DAO ownership extraction.
- Cancellation corrections.
- Worker allowlist cleanup.
- Worker regression tests.

## Commit B3

`ci(release): remove whole-file Gradle exemption`

Contains:

- DI scanner correction.
- Minification decision.
- Exact fallback only if necessary.
- Release configuration tests.

## Commit B4

`test(money): restore release-critical MoneyTest coverage`

Contains:

- Denylist restoration.
- Assertion rewrites.
- Removal of ignores.
- Critical-set policy test.

## Commit B5

`docs(ci): document exact exemption policy and verified results`

Created only after CI passes.

---

# 10. Validation commands

Run:

- Worker boundary guard in blocking mode.
- DI/release guard in blocking mode.
- Allowlist compliance guard in blocking mode.
- Ignored-test budget guard in blocking mode.
- All Python guard tests.
- Worker architecture JVM tests.
- `MoneyTest`.
- Full debug unit tests.
- Android lint.
- Debug assembly.
- Release assembly if minification changes.
- `:app:check`.

Run the static suite with the real repository allowlists, not only fixture allowlists.

---

# 11. Risks and mitigations

## Worker behavior regression

**Risk:** Moving DAO logic changes retries, transactions or scheduling.

**Mitigation:**

- Preserve worker names and unique-work policies.
- Characterize current outcomes before refactoring.
- Add coordinator contract tests.
- Test blocked, retry, failure and cancellation paths.

## R8/minification regression

**Risk:** Reflection, Room, Hilt, WorkManager or serialization breaks.

**Mitigation:**

- Compile and smoke-test release.
- Add narrow keep rules with explanations.
- Use temporary exact exception only if required.
- Do not restore a whole-file exemption.

## Overly strict allowlist migration

**Risk:** Historical allowlists fail unrelated CI.

**Mitigation:**

- Enforce strict schema first for worker, UI DAO and DI/release allowlists.
- Report legacy-format debt separately.
- Migrate remaining allowlists in scheduled follow-up work.
- Never grant new broad entries during transition.

## Money assertion rewrite

**Risk:** Tests pass while semantic precision is weakened.

**Mitigation:**

- Compare exact decimal/scalar values.
- Test currency identity separately.
- Avoid raw floating-point tolerances for core money behavior.

---

# 12. PR acceptance checklist

## Allowlist framework

- [ ] No whole-file/class early-return behavior.
- [ ] Exact path, symbol and subrule matching.
- [ ] Wildcards rejected for high-risk guards.
- [ ] Permanent unresolved debt rejected.
- [ ] Stale entries rejected.
- [ ] One exception cannot match multiple findings.
- [ ] Malformed config exits with code 2.

## Workers

- [ ] Six permanent worker entries removed.
- [ ] Every production DB worker uses `WorkerExecutionGuard`.
- [ ] No affected worker injects a mutating DAO.
- [ ] Cancellation propagates.
- [ ] Guard result maps through the canonical bridge.
- [ ] Blocked execution performs no DB work.
- [ ] Worker guard reports zero blocking findings.

## DI/release

- [ ] `app/build.gradle.kts` wildcard removed.
- [ ] Gradle file always scanned.
- [ ] Minification enabled or exact temporary exception documented.
- [ ] `isDebuggable=true` remains independently blocking.
- [ ] DI/release guard passes.

## Money tests

- [ ] `MoneyTest` restored to release-critical list.
- [ ] No ignored money tests.
- [ ] All money tests pass.
- [ ] Removing `MoneyTest` from policy causes CI failure.

## Evidence

- [ ] Full static suite passes.
- [ ] Guard pytest passes.
- [ ] Unit and architecture tests pass.
- [ ] Lint and `:app:check` pass.
- [ ] Release assembly passes if release config changed.
- [ ] Documentation references an actual successful Actions run.

---

# 13. Definition of done

PR B is complete only when:

- No production worker or release configuration file can bypass an entire guard.
- The six worker exemptions are gone.
- The Gradle wildcard exemption is gone.
- `MoneyTest` is active and release-critical.
- Every remaining exception matches one exact live finding.
- Temporary debt expires automatically.
- Permanent exceptions are limited to proven structural necessities.
- CI demonstrates that adding a second violation to an excepted file/class still fails.
- No new suppression, ignored test, wildcard, permanent debt entry or warning conversion was introduced.

The target is not “fewer guard failures.” The target is:

> **Every passing guard result represents code that was actually scanned and every exception is narrower than the safety invariant it preserves.**