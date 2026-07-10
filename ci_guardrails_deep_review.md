# CI Static Guardrails — Deep Review

**Reviewed target:** `ebb5aa93348282b31c1c669d1bf1271d584b9eb0`  
**Verdict:** **NOT READY TO MERGE / M0 NOT COMPLETE**

## 1. Executive verdict

The latest commit, `ebb5aa9`, is documentation-only: it adds `GUARD_VIOLATION_AUDIT.md` and changes no application, guard, test, or workflow code. The relevant implementation is primarily in its parents, especially `b202540` and `0bbb52e`. ([github.com](https://github.com/panospao7/Cost-agregator/commit/ebb5aa93348282b31c1c669d1bf1271d584b9eb0))

The actual GitHub Actions run for `ebb5aa9` failed:

| Job | Actual result |
|---|---|
| Validate Workflow | Passed |
| Static Guards | **Failed** at UI/ViewModel DAO guard |
| Unit Tests | **Timed out** after 30 minutes |
| Lint & Check | **Failed** during `lintDebug` |
| Instrumented Tests | Not run on this feature-branch push |

Therefore, `assembleDebug`, `:app:check`, later static guards, migration guard tests, ignored-test enforcement, and guard pytest were not all successfully verified. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29052459061))

**Bottom line:** the CI infrastructure is substantially better, but the current branch is red and some apparent “fixes” suppress findings rather than fix or safely baseline them.

---

# 2. Critical findings

## S0-1 — Four important guards were made non-blocking with `|| true`

Commit `0bbb52e` changed privacy, DB access, event-writer, and money guards to warning mode using `|| true`. This makes CI green regardless of their exit status and permits new violations as well as old violations. It is not a fail-on-growth baseline. ([github.com](https://github.com/panospao7/Cost-agregator/commit/0bbb52e))

Currently suppressed:

- Privacy: 1 known finding.
- DB access: 70 findings.
- Event writers: 45 findings.
- Money: 2 findings.
- Cancellation: 198 findings, also non-blocking.

This does not satisfy the implementation-plan acceptance criterion that new bad examples must be rejected by CI.

### Required correction

For every backlog guard:

1. Store an exact machine-readable baseline or exact allowlist.
2. Fail if the count increases.
3. Preferably fingerprint findings by rule, file, symbol, and normalized code location.
4. Require expiry for architectural debt.
5. Remove `|| true`.

A warning guard without growth enforcement is only an audit report, not a guardrail.

---

## S0-2 — PII guard is blocking while ten known violations remain

`b202540` promoted the PII guard to blocking while explicitly documenting ten remaining real violations. `0bbb52e` did not fix or suppress those ten. The current workflow still invokes it with `--fail-on-violation`. Therefore, even after fixing the earlier UI guard failure, static CI should fail when it reaches PII validation. ([github.com](https://github.com/panospao7/Cost-agregator/commit/b202540))

This directly contradicts:

- “CI can now pass.”
- “10 blocking guards passing.”
- The audit's simultaneous statement that PII has ten blocking violations.

### The audit’s PII remediation is partly unsafe

Recommendations such as:

- `Timber.e(e, "SAFE_CODE")`
- `Log.e(TAG, "SAFE_CODE", e)`

can still log the Throwable’s raw message, causes, and stack trace. If the original exception contains OCR text, filenames, URLs, email addresses, tokens, or merchant text, changing only the outer message does not make the log safe.

Likewise, truncating `rawOcrText` under `BuildConfig.DEBUG` is not an acceptable privacy boundary. Debug logs can still be collected, backed up, shared, or included in bug reports.

### Proper remediation

- Remove all raw OCR logging.
- Use a typed diagnostic API accepting only a safe reason code and approved metadata.
- Log exception class/category only when necessary.
- Do not pass the original Throwable to production logging unless the logger sanitizes the entire cause chain.
- Keep raw causes internal and ensure they cannot reach UI, diagnostics, analytics, or logs.
- Add guard fixtures proving Throwable arguments and nested causes are detected.

---

## S0-3 — The UI DAO issue was allowlisted, not fixed, and CI still fails

`BankConnectionsViewModel` still:

- Imports `BankConnectionDao`.
- Injects the DAO directly.
- Reads connections through it.
- Reads a connection by ID.
- Calls `bankConnectionDao.disconnect()` directly.

The permanent allowlist says the ViewModel “requires DAO access,” but this contradicts the repository/lifecycle architecture and MIT-060. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/allowlists/ui_dao_allowlist.yml))

The latest Actions run nevertheless fails at `Verify UI/ViewModel DAO boundaries`, so the allowlist did not produce the expected clean result or additional violations remain. Public annotations do not expose the exact emitted lines. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29052459061/job/86236310413))

### Correct fix

Introduce a bank-connection repository or lifecycle coordinator that owns:

- Connection observation.
- Synchronization initiation.
- Disconnect mutation.
- Write-barrier checks.
- Operation diagnostics.
- Cancellation propagation.

The ViewModel should depend only on that abstraction.

Additional problems in the same ViewModel include broad `catch (Exception)` in `viewModelScope` and logging the connection ID alongside the raw Throwable. These should be corrected during the extraction.

### Traceability error

The allowlist links to `MIT-003`, while the concrete issue is `MIT-060`. This weakens expiry and ownership tracking.

---

## S0-4 — The UI guard has fail-closed and diagnostic defects

The UI guard implementation silently handles configuration and source-reading errors:

- YAML import/parse exceptions are swallowed and treated as an empty allowlist.
- File read errors return no violations.
- The former fatal-error block was deleted rather than implementing proper error collection.

This violates the documented framework contract that script/configuration errors exit with code 2. It can cause either unexplained false failures or, for unreadable source files, false passes. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_ui_dao_boundaries.py))

### Required fixes

- Invalid or unreadable YAML: exit 2 with file and parser error.
- Missing required allowlist: exit 2.
- Unreadable source file: exit 2.
- Add CLI-level tests for all three cases.
- Test the real repository allowlist, not only temporary fixture YAML.
- Require exact normalized repository paths rather than permissive suffix matching.
- Use POSIX-normalized path parts consistently on Windows and Linux.

The existing tests cover basic positive/negative examples but not these failure modes. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/test_verify_ui_dao_boundaries.py))

---

## S0-5 — DI/release guard was weakened by exempting the entire Gradle file

The DI fix added `app/build.gradle.kts` to the DI allowlist and modified `scan_gradle_file()` to return immediately for an allowlisted file. Consequently, all present and future release-boundary checks in that file can be hidden—not merely the intended `isMinifyEnabled=false` finding. ([github.com](https://github.com/panospao7/Cost-agregator/commit/0bbb52e))

That is especially dangerous because `app/build.gradle.kts` is the primary release configuration surface.

The justification that “ProGuard is configured separately if needed” is not sufficient: rules files do not provide shrinking/obfuscation when minification is disabled.

### Correct fix

- Never exempt the whole Gradle file.
- Allowlist only the exact rule and property.
- Link it to MIT-028, not generic MIT-003.
- Use a short expiry, not `permanent`.
- Add a release build job.
- Verify release manifest, cleartext configuration, HTTP logging, debug/stub bindings, secrets, and minification policy from the built release artifact.

Current CI builds only the debug variant, so the DI source scanner cannot establish release safety by itself. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/.github/workflows/ci.yml))

---

## S0-6 — Real migration tests exist but are not a blocking PR gate

PR11 added useful instrumented tests for:

- `145 → 146`
- `146 → 147`
- `147 → 148`
- Full `145 → 148`
- Fresh-versus-migrated parity
- Pre-baseline destructive fallback

This is a meaningful improvement. ([github.com](https://github.com/panospao7/Cost-agregator/commit/1194672))

However, those tests are under `androidTest`. The workflow:

- Does not run instrumented tests for feature-branch pushes or pull requests.
- Runs them only on `main`/`master` pushes or manual dispatch.
- Marks them `continue-on-error: true`.

Therefore, a broken migration can still merge while all required checks pass. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/.github/workflows/ci.yml))

### Additional migration-test weaknesses

- Required schema checks use `assumeTrue`, allowing tests to skip when snapshots are missing.
- Migration baseline and current version are hardcoded.
- Versions 33–144 are deliberately destructive, but the release/user-data policy is not proven merely by testing that destructive opening succeeds.
- The static migration script validates registration, not actual SQL execution.

### Required correction

Create a dedicated blocking migration job for PRs, at least whenever database, entities, migrations, or schemas change.

For supported versions:

- Missing schema must fail, not skip.
- Current version should derive from the production schema-version source.
- Fresh-versus-migrated parity must compare tables, columns, indexes, foreign keys, triggers, defaults, and uniqueness constraints.
- Instrumented migration failure must block merging.
- Destructive fallback must have an explicit product policy and user-safe migration/import path.

---

# 3. Worker architecture assessment

## What appears good

The earlier worker architecture work provides a strong intended foundation:

- `WorkerExecutionGuard`.
- Lease/drain tracking.
- Write-barrier integration.
- Run logging.
- Privacy/permission policies.
- Static worker discovery.
- Receiver cleanup.

The current CI also has a dedicated worker guard and seeded tests, which is directionally correct.

## What is not proper in the recent CI fix

`0bbb52e` made six worker classes pass by adding permanent allowlist entries rather than refining the guard or changing worker code:

- `LocationBackfillWorker`
- `MerchantKeyBackfillWorker`
- `DataRetentionWorker`
- `ReceiptMatchingWorker`
- `BillReminderWorker`
- `WarrantyExpirationWorker`

The commit reports that 16 findings became zero through these exemptions. ([github.com](https://github.com/panospao7/Cost-agregator/commit/0bbb52e))

This means the CI change does not independently prove those workers comply with every G-WORKER requirement. A class-level permanent exemption risks hiding later removal or relocation of the execution guard, new DAO access, or incorrect result handling.

### Important cancellation point

An outer `WorkerExecutionGuard` cannot restore cancellation after inner code has already caught `CancellationException` through `catch (Exception)` or `runCatching` and converted it into `Result.success`, fallback data, or a normal failure result.

Therefore, the audit's “LOW because most are guarded by WorkerExecutionGuard” classification is too optimistic. Cancellation risk is at least medium in:

- Backup/restore.
- Transaction lifecycle.
- Receipt lifecycle.
- Maintenance operations.
- Workers that claim or mutate durable state.

### Worker guard tests that are still needed

The guard must reject seeded examples where:

1. `WorkerExecutionGuard` is imported but never invoked.
2. The guard call is in dead/unreachable code.
3. A DAO read occurs before guard entry.
4. A DAO mutation occurs outside the guarded lambda.
5. Cancellation is swallowed inside the worker body.
6. Blocked maintenance returns success instead of retry.
7. Terminal run state can be written twice.
8. A worker is omitted from the registry/FQN inventory.
9. An allowlisted worker later removes its execution guard.
10. Guard, lease, barrier, and run ledger are not part of the same execution scope.

### Recommended allowlist policy

If a worker legitimately owns a direct mutation:

- Exempt only the exact rule and symbol.
- Require proof that the mutation executes inside the guarded scope.
- Use an expiry for transitional cases.
- Never exempt the entire worker from guard-presence checks.
- Separate “legal direct DAO owner” from “may omit WorkerExecutionGuard”; the latter should normally have no allowlist.

---

# 4. Guard-by-guard status

| Area | Assessment |
|---|---|
| Workflow/actionlint | Good foundation; actually passes |
| Full Gradle verification | Wired, but not currently passing |
| Existing Python guards | Mechanically wired; four major guards suppressed |
| UI DAO guard | Failing; underlying architecture violation remains |
| Worker guard | Useful, but recent pass obtained through broad exemptions |
| Receipt-link guard | Not reached in latest run |
| Import lifecycle guard | Not reached in latest run |
| Cloud payload guard | Not reached in latest run |
| PII logging | Ten known blockers remain |
| DI/release | False confidence from whole-file Gradle exemption |
| Cancellation | 198 unresolved and no fail-on-growth enforcement |
| DB access | 70 unresolved and no fail-on-growth enforcement |
| Event writers | 45 unresolved and no fail-on-growth enforcement |
| Privacy | One unresolved; suppression allows regressions |
| Money | Two unresolved; suppression allows regressions |
| Migration registration | Useful static coverage |
| Migration execution | Useful tests, but non-blocking/skippable |
| Ignored-test budget | Better baseline mechanism, but policy weakened |
| Guard pytest | Not reached in latest static run |
| Branch protection docs | Useful documentation, not evidence that checks pass |

---

# 5. Ignored-test review

Promoting the Python ignored-test budget to blocking with a baseline near the current count is better than the Gradle threshold of 310.

However:

1. The Gradle threshold of 310 is inconsistent with a Python baseline of 29 and permits enormous growth if the Python guard is skipped.
2. Removing `MoneyTest` from the release-critical denylist is not a proper resolution.
3. “Truth boxing is legitimate” explains why a particular assertion fails; it does not justify ignoring critical money behavior.
4. The correct action is to rewrite those assertions using compatible comparisons or underlying values.

Restore `MoneyTest` to the release denylist and activate its critical tests.

Use one baseline source for both Gradle and Python so the two guards cannot drift. Commit `0bbb52e` explicitly removed `MoneyTest` from the denylist to make the guard pass. ([github.com](https://github.com/panospao7/Cost-agregator/commit/0bbb52e))

---

# 6. Audit recommendation corrections

## Sensitive `hashCode()`

The audit recommends replacing `transactionId.hashCode()` inside `TransactionContext.hashCode()` with an HMAC service.

That should not be applied blindly.

If this is an ordinary Kotlin/Java equality/hash implementation:

- `hashCode()` is an object-collection contract, not a secure external identifier.
- Injecting a Keystore hashing service into a value/context object is architecturally awkward.
- Install-specific HMAC output can make hashes unstable across restore/process scenarios.
- Converting the HMAC back to an `Int` may provide no meaningful privacy benefit.

First determine whether this value is persisted, logged, exported, or exposed as a fingerprint. If external identification is needed, create a separate typed safe identifier using `SensitiveHashingService`. If it is only the in-memory object hash contract, use a narrow justified guard exemption rather than changing object semantics.

## Dashboard money finding

Do not inject `MultiCurrencyRepository` into the middle of widget arithmetic simply to silence the guard.

The preferred design is:

1. Normalize all monetary input at the use-case boundary.
2. Represent it with a typed `MoneyAmount`/normalized-money model.
3. Pass the normalized home-currency budget and spending values into computation.
4. Avoid raw `Double` for financial values.

If `ctx.totalBudgetAmount` is already contractually normalized, rename/type it so the guard can prove that fact. Otherwise, fix normalization before constructing the context.

## DB/event findings

Some direct writes may be canonical infrastructure exceptions, but allowlisting alone does not prove safety:

- `DatabaseMigrations`: dedicated raw-SQL scope is appropriate.
- `FinancialRescueCoordinator`: must require maintenance ownership and exclusive DB handling.
- Receipt lifecycle services: should use typed writers and transaction context.
- `OperationRunRecorder`: needs an explicit decision about whether diagnostics live in the main DB during maintenance.
- Backfill workers: must prove guarded execution and lease ownership.
- Restore journal replay: needs a dedicated replay/import writer.

---

# 7. CI operational defects

## Unit-test timeout

The latest unit-test job exceeded its 30-minute limit during the first Gradle test step. Consequently, schema verification, ignored-growth verification, and currency guardrails did not run. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29052459061/job/86236310417))

Fix by:

- Initially increasing the timeout to obtain complete diagnostics.
- Splitting architecture/contract tests from normal unit tests.
- Finding hanging or exceptionally slow tests.
- Avoiding redundant Gradle invocations.
- Sharding if necessary.
- Keeping a separate deterministic timeout for known stress tests.

Do not treat a timeout increase alone as the final fix.

## Lint failure

`lintDebug` failed before `assembleDebug` and `:app:check`, so the latest run provides no evidence that either later step succeeds. The precise lint diagnostic is not visible in the public unauthenticated job annotation, but the job result is definitive. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29052459061/job/86236310406))

Resolve all lint errors and rerun the complete job.

## External Kotlin CLI dependency

The attached local-CI guide states that Gradle-wired `.kts` guards require `kotlin` on `PATH`. JDK setup alone does not establish that dependency.

Remove this environmental assumption by moving those checks to:

- JVM architecture tests,
- a Gradle/Kotlin implementation using project dependencies, or
- Python guards already installed in CI.

## Poor guard failure artifacts

The latest static job attempted to upload `scripts/*.log` and `scripts/output/`, but neither existed. GitHub emitted a warning, leaving no usable guard artifact. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29052459061))

Each guard should write deterministic output to a known file, or the workflow should pipe output into per-guard logs. A final aggregation step should publish all findings even when one guard fails.

---

# 8. Documentation inconsistencies

The attached documentation does not match the final workflow:

- Baseline docs describe privacy, DB, event, and money as blocking; workflow makes them warnings.
- Docs describe PII as warning; workflow makes it blocking.
- Docs variously describe DI and ignored-test guards as warning or blocking.
- Cancellation counts differ between 248 and 198.
- Ignored-test counts differ among 29, 31, and 32.
- `LATEST_CI_VERIFICATION.md` says jobs are “Expected,” not actually verified.
- `GUARD_VIOLATION_AUDIT.md` claims blocking guards pass while documenting ten blocking PII findings.
- The master tracker still reports P17 RED/high-YELLOW, which is more accurate than the newer “CI can now pass” messaging.

Generate guard status documentation from actual CI output rather than maintaining counts manually.

A valid verification record should contain:

- Exact commit SHA.
- Actual Actions run ID.
- Every job conclusion.
- Every guard mode and count.
- Skipped jobs/tests.
- Explicit exclusions.
- Date of successful execution.

---

# 9. Recommended corrective PR sequence

## PR A — Restore truthful CI

1. Fix the direct DAO architecture in `BankConnectionsViewModel`.
2. Fix all ten PII findings using a genuinely sanitized diagnostics API.
3. Fix current lint errors.
4. Diagnose and split/extend the unit-test job so it completes.
5. Ensure the static job reaches all guards and pytest.
6. Publish per-guard logs.

## PR B — Remove unsafe exemptions

1. Remove the permanent UI DAO exemption.
2. Replace worker class-wide exemptions with rule/symbol-specific ownership entries.
3. Remove the whole-file `app/build.gradle.kts` DI exemption.
4. Restore `MoneyTest` to the release denylist.
5. Correct linked issues, especially MIT-060 and MIT-028.
6. Prohibit permanent exemptions for unresolved debt.

## PR C — Enforce no-growth backlogs

For cancellation, DB access, event writers, privacy, and money:

1. Establish exact current baselines.
2. Fail on every new fingerprint.
3. Burn down existing findings incrementally.
4. Reject expired or stale entries.
5. Remove `|| true`.

## PR D — Make migration proof blocking

1. Add a blocking migration-instrumentation PR job.
2. Remove `continue-on-error` for migration tests.
3. Replace `assumeTrue` with assertions for required snapshots.
4. Derive the latest DB version.
5. Test supported migration starts and full schema parity.
6. Document the destructive pre-v145 policy.

## PR E — Add actual release verification

1. Compile and lint release.
2. Verify release DI bindings.
3. Scan for fake/stub/no-op implementations.
4. Check cleartext/network logging.
5. Scan secrets and API keys.
6. Decide and enforce minification policy.
7. Inspect the resulting APK/AAB rather than relying solely on source regexes.

## PR F — Harden guard implementation

1. Fail with exit 2 on configuration/read errors.
2. Add malformed-YAML and unreadable-file tests.
3. Test actual repository allowlists.
4. Require exact paths and symbols.
5. Add seeded bypass patterns.
6. Gradually replace fragile regex scans with AST/compiler-based checks for Kotlin architecture rules.

---

# 10. Final acceptance gate

Do not call the CI guardrail plan complete until one commit has an actual run where:

- Validate Workflow passes.
- Static Guards passes and reaches every guard plus pytest.
- Unit Tests complete without timeout.
- Room schema verification passes.
- Ignored-test budget passes.
- Currency guardrails pass.
- Lint passes.
- Debug assembly passes.
- `:app:check` passes.
- Blocking migration execution passes.
- Release verification passes.
- Warning backlogs reject growth.
- No security/architecture guard is neutralized by `|| true` or a whole-file permanent exemption.

## Final conclusion

**The architecture and CI direction is good, particularly the workflow separation, actionlint, guard inventory, migration tests, and worker-execution model. But the recent changes do not currently solve the CI issues.**

The principal mistake is equating “the guard no longer blocks” with “the issue is fixed.” In several places—UI DAO, worker boundaries, DI/release, ignored money tests, and warning guards—the result was achieved through broad exemptions or disabled enforcement.

The current branch should remain **RED** until the actual Actions run is green without suppressing high-risk findings.