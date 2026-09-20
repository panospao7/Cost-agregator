# RP-00 — Decision Register: RECORDED ANSWERS

> Recorded 2026-09-14 at campaign kick-off (base `444e5ad7`).
> Provenance: stakeholder approved the RP-00 recommended sheet ("lets start"
> after the recommendation was tabled); each line below may be amended by a
> one-line follow-up — amend the table and note the date, do not silently
> rewrite history.

| ID | Decision (as recorded) | Effect |
|---|---|---|
| D1 | **A** — hide the bank-sync surface in release builds (`BuildConfig.DEBUG` gating on nav/settings entry) | RP-17 fixes the debug-only cluster without release pressure; real provider = separate feature project |
| D2 | **A** — email ingestion declared **staged**: fix parsers now, correct docs, leave service unwired behind `TODO(F14-wiring)` | RP-18 covers parsers + docs only |
| D3 | **a** — CSV import stays debug-only; importer correctness fixed anyway | RP-19 proceeds; release promotion is a separate feature decision |
| D4 | **approve all 8 deletions** (NotificationRepository.deleteAll, ExpenseDao.updateMerchantForMerchant, ProcessReceiptUseCase, RecurringPlanProjectionService.projectFromRule method only, ImportCoordinator per RP-19 ownership, BankConnectionsViewModel.refresh, CloudWarrantyExtractionService sanitizer copies, captureForRetry policy note per RP-10) | RP-20 executes each as its own tiny commit with zero-caller grep quoted |
| D5 | **a** — wire the three inline write sites to `RawPersistencePolicyResolver` | Folded into RP-15 as its first step (strict-privacy review) |
| D6 | **approve** — `NotificationIdGenerator` becomes the only ID source for all `notify()` calls; extend/add ranges; add architecture guard | Informs RP-16 (P9-001) |
| D7 | **approve** — the month-end correction jump (past PAID rows keep drifted dates; next occurrence restores the anchor day) | Informs RP-04 (P4-005); wording per RP-04: "prevents new drift from the current expansion anchor" |
| D8 | **after remediation** — RP-21 triages the ~178-failure suite only after remediation PRs land | RP-21 strictly last (wave 5) |

## Campaign execution context (recorded alongside)

- Execution base: `atomicity-pr21-enforcement-final` @ `444e5ad7` (integration line; `main` is 992 commits stale and is NOT the base).
- Wave structure: W1 = RP-01→11 ∥ RP-02→04 ∥ RP-03 · W2 = RP-05→06→07→08→09 ∥ RP-10 ∥ RP-12→13 · W3 = RP-14→16 ∥ RP-15 · W4 = RP-17 ∥ RP-18 ∥ RP-19 · W5 = RP-20→21.
- Parallel-safety rules: one lane = one worktree = one branch; chains never parallelized internally; at most one Room-schema-bumping RP in flight repo-wide at any time; Gradle capped at 2 concurrent invocations, `--tests`-filtered only (pre-existing ~178 suite failures are not a gate until RP-21).
- GR-15 interaction: RPs that restructure DB writers (notably RP-02, RP-04, RP-11, RP-14) will change the mediation board; the owner-acceptance registry is fail-closed and must be regenerated from a cold board after those waves — an expected `GR15_ACCEPTANCE_ROW_UNMATCHED` is the gate working, not a regression.
- Open at recording time: merge of `gr-14f-wip` (233 commits, finished GR-13/14/15 gate campaign) into the integration line — recommended before RP-02's first merge (same writer/barrier surface), stakeholder to trigger.

| ID | Decision (as recorded) | Effect |
|---|---|---|
| D9 | 2026-09-20 — RP-16 worker counters: **durable Room columns** (immutable `WorkerRunCounters` persisted as real columns; DB version bump + migration + schema snapshots + migration tests) | RP-16 counter batches executable; measured-zero is provable across restarts |
| D10 | 2026-09-20 — RP-13 Gate A: **explicit stable candidateId** contract (id derived from parser source-line identity; validate integer/range, uniqueness, 1:1 ownership; `AI_IDENTITY_MISMATCH` → parser-only fallback on any violation) | RP-13 identity gate unblocked |
| D11 | 2026-09-20 — RP-13 Gate B: statement/source currency only when explicitly known + validated; otherwise typed `CURRENCY_UNKNOWN` skip; **no home-currency fallback** | RP-13 currency policy fixed; hard-coded EUR default removed |
| D12 | 2026-09-20 — RP-17 remaining gates: bank pending-review identity = **cross-run source-fingerprint contract**; sync status = **terminal-only in DB + ViewModel in-flight state (no migration)**; D1 release/provider gating reaffirmed; provider cursor work stays deferred/gated | RP-17 batches 17-A..E executable |
