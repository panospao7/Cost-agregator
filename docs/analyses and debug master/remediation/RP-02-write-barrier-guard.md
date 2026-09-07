# RP-02 — Write-barrier guard repair + writer registration

> **Scope class:** Universal (architecture guard + writers in 4 pipelines). **Mode:** strict.
> **Crossover:** the detection upgrade also closes the `RecurringArchitectureGuardTest` alias gap used by P4-002 (RP-04) and covers `DataRetentionWorker` audit writes touched by RP-14. Land before both.

## U-004 — Barrier guard cannot see three real unbarriered DAO writers; materializer unexempted

**Problem.** `WriteBarrierArchitectureGuardTest.kt:239-256` detects DAO-write callers only when the receiver variable is named exactly like the lowercased DAO interface (or via the `database.xxxDao().method(` accessor chain). Verified escapes:
- `NotificationIntakeCoordinator` — field `intakeDao: NotificationIntakeDao`, writes at :125/:214 (`insertOrIgnore` = `@Insert(IGNORE)` write), **no barrier injected**.
- `SourceLinkWriterImpl` — field `sourceLinkDao: EntitySourceLinkDao`, writes at :48/:85, no barrier.
- `DataRetentionWorker` — local `val auditDao = appDatabase.privacyAuditDao()` (:183), writes :196/:206 — the local-variable indirection defeats both regex shapes.
- `RecurringOccurrenceMaterializer` — field `plannedExpenseDao` (matches!), write `fulfillByOccurrenceKey` at :123 (a `@Query("UPDATE ...")`), class has **no barrier** and is not in `EXEMPT_CLASSES` — so the guard's core assertion should currently be **red**. (Do not "fix" this by silently adding an exemption — the write must be justified or routed.)

## Fix design

### Part 1 — guard detection upgrade
Replace/augment `buildCallerRegexes` with a **class-level contract check** (deterministic, no type inference needed):
1. Build the registry of "restricted DAO write methods" as today (interface name + method names incl. `@Query`-mutating methods — already implemented via `queryTriplePattern`).
2. For every production source file, detect **constructor-injected DAO fields** with a regex that tolerates real declaration shapes (verified):
   - `private\s+val\s+\w+\s*:\s*(?:[\w.]+\.)?(RegisteredDaoName)` — the FQTN prefix group is **required**: `RecurringOccurrenceMaterializer.kt:36` declares `private val plannedExpenseDao: com.yourname.expensetracker.data.database.dao.PlannedExpenseDao`.
   - Plus an **unanchored** `val\s+\w+\s*=\s*(?:appDatabase|database|db)\.(registeredDao)\w*\(\)` pattern — `DataRetentionWorker.kt:183` is a *local* `val auditDao = appDatabase.privacyAuditDao()` inside a function body, not a field.
   - Note: `buildBarrierProtectedClasses` (`:224-226`) only recognizes barrier injection when the constructor parameter is literally named **`writeBarrier`** — Part 2's injections and all new test fixtures must use that parameter name.
3. For each such class, require **at least one** of:
   - the class text references `checkWritesAllowed` / `DatabaseWriteBarrier` / `executionGuard` / `checkpoint(`; or
   - the class is listed in `EXEMPT_CLASSES` **with a mandatory justification string** — change `EXEMPT_CLASSES` from `Set<String>` to `Map<String, String>` (class → reason). A bare name entry fails the test. Only two use sites consume it today (`:292` membership check — works on Map keys unchanged; `:360` filter — needs `.keys`).
4. Keep the existing method-level caller regexes as an additional (not sufficient) check.

This catches field-alias receivers (`intakeDao`, `auditDao`) and future aliases like `subscriptionDao` (the P4-002 evasion of `RecurringArchitectureGuardTest` gets the same treatment there).

### Part 2 — writer remediation (one of: add barrier / route / exempt-with-justification)

| Writer | Decision | Change |
|---|---|---|
| `NotificationIntakeCoordinator.kt` (:125, :214) | **Add barrier** | Inject `DatabaseWriteBarrier` as a constructor parameter **named `writeBarrier`** and call `writeBarrier.checkWritesAllowed("notification.intake.capture")` (String overload, `DatabaseWriteBarrier.kt:11`) at the top of `capture()` (:32) and `captureForRetry()` (:157) before the first DAO write. Rationale: intake performs DB writes outside any worker guard. |
| `SourceLinkWriterImpl.kt` (:85 — the only DAO *write*; `:45-48` is an `exists` read) | **Add barrier** (re-evaluation overturned the exemption plan) | The original "all callers are barrier-checked coordinators" claim is **false**: besides the checked callers (TransactionLifecycleCoordinator:615 via :293; ReceiptLifecycleCoordinator:913; NotificationProcessingPipeline:665 via :187/:212; ReceiptLinkService:339 via :161), `PendingReviewSourceLinkServiceImpl.kt:52` and `PendingReviewSourceLinkPromoterImpl.kt:58/:86` invoke the writer **without any barrier** (neither class injects one). Inject `DatabaseWriteBarrier` (param named `writeBarrier`) into `SourceLinkWriterImpl` and check at the top of the write path. |
| `DataRetentionWorker.kt` (:196, :206) | **Add explicit check** | The worker runs under `WorkerExecutionGuard.runGuardedWithContext` (:77) with per-target checkpoints (:128), but the audit writes at :196/:206 happen after the last checkpoint (clearCheckpoint at :180) — add one explicit `writeBarrier.checkWritesAllowed("privacy.retention.audit")` before the purge loop. Privacy cleanup must stay runnable: the check uses the same restore-blocked semantics as the guard's checkpoint (SKIPPED, not failure) — do **not** introduce new capability gating (AGENTS: cleanup must never be gated on the capability it enforces). |
| `RecurringOccurrenceMaterializer.kt` (:123 **and** :172) | **Exempt-with-justification** | Both `fulfillByOccurrenceKey` call sites (:123, :172) are invoked from barrier-checked coordinator transactions — all **four** call sites verified: `RecurringLifecycleCoordinator.kt:145` (check at :94), `RecurringRuleLifecycleCoordinator.kt:156/:216/:323` (checks at :132/:194/:277). Add `EXEMPT_CLASSES["RecurringOccurrenceMaterializer"] = "write occurs inside caller-held barrier-checked transactions (4 verified call sites)"`. |

### Part 3 — sibling guard alignment
Apply the same constructor-injection detection to `RecurringArchitectureGuardTest.kt:48-55` so `subscriptionDao` (alias of `ManualRecurringExpenseDao`) is recognized (needed by RP-04/P4-002).

## What it solves
- CI actually enforces the write barrier for every DAO-writing class, including aliased/local receivers.
- The four invisible writers become visible and either barrier-checked or explicitly, reasoned exemptions.
- Restores the guard's truthfulness (it should not be red for the wrong reason or green by blindness).

## Guardrails
- **Do not weaken the guard to get green** — exemptions require justification strings and reviewer attention (AGENTS: no weakening architecture guards without explicit approval; the justifications *are* that approval artifact).
- `EXEMPT_CLASSES` map migration will fail compilation at use sites — update all in this PR.
- No schema/behavior change to the writers themselves except the two added barrier checks (both no-ops in NORMAL mode).
- Crossover: RP-14 adds retention targets — the worker-side check added here must not contradict the new purge targets (order: RP-02 → RP-14).

## Tests
- Guard test: fixture classes — (a) aliased injected DAO, no barrier → fail; (b) aliased + barrier → pass; (c) local `appDatabase.xDao()` val + no barrier → fail; (d) exempted with reason → pass; (e) exempted without reason → fail.
- Keep a regression entry asserting `EXEMPT_CLASSES` count does not grow without a corresponding reason map entry (implicit in the map typing).
- Integration: `RestoreBlocksAllWritesTest`-style test extended: intake capture during restore → typed blocked outcome (not a raw write).

## Validation (when Gradle is re-enabled)
```
./gradlew :app:testDebugUnitTest --tests "*WriteBarrierArchitectureGuard*" --tests "*RecurringArchitectureGuard*" --tests "*RestoreBlocksAllWrites*"
```

## Sequencing & risk
- Land before RP-04 and RP-14. Risk: low-medium — Part 1 is test-only until Part 2's two barrier injections (small, fail-closed additions).
