# Wave 4 + Money Aggregate Batch Plan

Date: 2026-10-01

Base: `a25f843a` (`bug-fixes`, Wave 3 pushed)

Status: CHECKPOINT AUTHORIZED — W4-1 through W4-7 source repairs have user-run validation evidence. Final post-fix strict review and deferred device gates remain open; this is not a release-closure claim.

## Closing checkpoint evidence (2026-10-03)

- The closing sweep has 53 durable run records at fingerprint `e375fbbfb1ff1e9c412faf17251b54e98b7cb262c62092532b9420db349ea05d`: compile, 49 targeted filters, migration-filtered unit tests, and the registered cancellation guard passed; static guards failed with 24 of 25 guards passing.
- Cancellation reconciles at 118/118 after the explicitly authorized removal of four resolved `ReceiptAssetStore` findings. No baseline growth or raw-money suppression was authorized.
- Static run `vr-20261003-164616-50beeeac` exited 1 with `raw_money_aggregates` as its sole blocking violation: 83 findings across 28 files. This remains separate money-architecture debt.
- The subsequent worktree fingerprint change was reconciled read-only: removing only the final closing journal entry reconstructs the exact validated fingerprint. No post-sweep source change was found.
- The user authorized a scoped checkpoint commit and push, not an all-green guard result or a passed final review. See `wave4-checkpoint-20261003-handoff.md` for the remaining gates and commit boundary.

## Operating rules

- Re-audit every finding against the current `a25f843a` source before editing. The Wave 4 verification document was collected at an older pin, and Wave 3 touched receipt, lifecycle, policy, and importer paths.
- Keep Wave 4 behavior fixes separate from the raw-money migration. The money work is cross-cutting and must not be hidden inside unrelated fixes.
- Implement one batch at a time on a clean Wave 4 lane. The current `bug-fixes` worktree contains unrelated dirty files; do not include them in any Wave 4 commit.
- Use strict review before each validation handoff. Validation remains serialized and is run by the user through `scripts/validation-runner.ps1`.
- Do not change guard baselines, raw-money allowlists, or exceptions to make a batch green. The inherited `raw_money_aggregates` failure remains visible until its findings are actually removed or explicitly re-adjudicated.
- Re-audit notification-intake ownership rows against the current worker and policy source before commit; obsolete rows must not be carried forward as historical reconciliation claims.
- A batch is complete only when its code, focused tests, strict review, and relevant guard evidence all match the same worktree fingerprint.

## Historical starting point (before Wave 4)

- Wave 3 is pushed at `a25f843a`.
- Wave 3 device gates remain intentionally deferred by the user and are outside this plan.
- The last reviewed guard disposition was `24/25`, with only inherited `raw_money_aggregates` failing; final post-remediation validation is still required.
- Raw-money disposition: the latest reviewed guard artifact reported 83 inherited findings separately tracked; W4-4's temporary `BudgetVsActualEngine` finding was routed through the approved money boundary. A fresh guard run is still required.
- No raw-money baseline, allowlist, or exception expansion is authorized.

## W4-0 current-source disposition

Refreshed locally against `a25f843a` on 2026-10-01. This replaces the older
verification pin for batch routing; it is a scope result, not validation evidence.

| Finding | Current disposition | Batch / current evidence |
|---|---|---|
| `CA-P-01-002` | Active; deferred capture still uses `REPLACE` and notification-key identity | `W4-1`; `NotificationIntakeCoordinator.kt` capture-for-retry path |
| `CA-P-01-003` | Active; a not-yet-due retryable row can return normal success after claim returns zero | `W4-1`; `NotificationIntakeWorker.kt`, `NotificationIntakeDao.kt` |
| `CA-P-01-005` | Fixed in current source; metadata, claim, payload load, and decrypt are inside the worker guard | Revalidate with `NotificationIntakeWorkerTimeoutTest` |
| `CA-P-02-001` | Latent/unwired; no production caller found for the cross-product linking path | Keep unwired; add ownership matching only if production reachability changes |
| `CA-P-01-007` | Active documentation drift | `W4-7` |
| `CA-P-03-005` | Active legal-path documentation drift; current receipt entry is `createExpenseAndLinkReceipt` | `W4-7` plus architecture guard check |
| `CA-E-05-001` | Active policy-vs-document reconciliation | `W4-7`; preserve enforcement policy |
| `CA-E-05-002` | Active legal-path documentation drift | `W4-7` |
| `CA-I-02-001` | Active KDoc plus stale callback-related test contract | `W4-7`; inspect guard anchors before editing |
| `CA-I-03-001` | Active segment documentation attribution drift | `W4-7` |
| `CA-I-03-002` | Active retention-target count drift across documentation surfaces | `W4-7` |
| `CA-P-04-001` | Source repair applied; current validation pending | SENT action transitions plus deterministic notification cancellation |
| `CA-P-04-002` | Source repair applied; current validation pending | `FAILED_TRANSIENT` due selection and atomic claim |
| `CA-P-04-003` | Source repair applied; current validation pending | inactive-rule update short-circuit |
| `CA-P-04-004` | Source repair applied; current validation pending | explicit anchor-day handoff in `RecurringLifecycleCoordinator` |
| `CA-P-05-001` | Source repair applied; current validation pending | week slice uses the widened six-month source window |
| `CA-P-05-002` | Source repair applied; current validation pending | trend values are ordered by chronological month key |
| `CA-P-06-002` | Source repair applied; validation pending | hierarchy scaling preserves the overall cap and returns an explicit non-actionable result when per-budget lower bounds are infeasible |
| `CA-E-02-001` | Source repair applied; current validation pending | overall budget actuals include categorized and uncategorized purchases |
| `CA-E-02-003` | Source repair applied; current validation pending | quality counts use affected transaction counts |
| `CA-E-02-004` | Source repair applied; current validation pending | recurring committed terms contribute to projected points |
| `CA-P-07-007` | Implemented in W4-5; validation pending | resolved backup flags, manifest metadata, and receipt assets stay aligned |
| `CA-P-11-001` | Source repair applied; validation pending | explicit symbol/ISO conflicts now fail closed |
| `CA-P-11-002` | Source repair applied; validation pending | summary-row totals bind to the amount after the total label |
| `CA-P-12-002` | Source repair applied; validation pending | format changes invalidate and cancel stale export work |
| `CA-I-05-003` | Implemented in W4-5; validation pending | visual split financial fields stay out of persisted navigation tokens |

### W4-1 implementation note

The first code batch changes only notification intake reliability. Deferred
capture now uses `notification-intake-{rowId}` with `KEEP`, and a retryable row
whose `nextAttemptAt` has not arrived returns a retry result instead of silently
completing the WorkManager chain. The row-level failure boundary now covers
metadata reload, payload load, and transient-payload decryption so claimed rows
cannot remain stranded after a preprocessing failure. Focused validation remains
pending.

### W4-2 implementation note

The reminder batch now allows SENT action buttons to transition through the
coordinator and cancels the deterministic bill notification ID after either
action. FAILED_TRANSIENT rows are selected and claimed by the normal due path.
Inactive rule updates persist only the rule snapshot/event, and projection and
generation carry the original anchor day through clamped catch-up dates.
Notification cancellation is isolated from channel setup and is best-effort after
the core mutation while coroutine cancellation still propagates. Focused
validation remains pending.

### W4-3 implementation note

The dashboard week aggregate now slices the widened source window directly,
preserving purchases from the previous month when the current calendar week
crosses the month boundary. Lifestyle income and spending trends now sort
month keys chronologically before calculating oldest-to-newest change.

## Recommended implementation order

### W4-0 — Current-source scope refresh

**Purpose:** Reconcile the 26 Wave 4 findings with the post-Wave-3 source before code changes.

**Work:**

- Confirm each finding's current file, caller reachability, owning segment, and existing test surface.
- Mark findings as active, already fixed by Wave 3, unwired/staged, or documentation-only.
- Record exact target files and acceptance tests for the batches below.
- Verify guard anchors before changing `LEGAL_PATHS.md`, policy documentation, or migration-related docs.

**Exit:** A checked-off finding matrix pinned to the clean Wave 4 lane. No production edits in this batch.

### W4-1 — Notification intake reliability (CL-02 / CL-03 / CL-04)

**Findings:** `CA-P-01-002`, `CA-P-01-003`, `CA-P-01-005`, `CA-P-02-001`.

**Fix shape:**

- Use a per-review-row WorkManager identity and `KEEP`; do not let one Android work key replace another pending row.
- Reconcile WorkManager backoff with the row retry ladder so a claimed row cannot return normal success while remaining `FAILED_RETRYABLE`.
- Move claim, payload load, and decrypt under the worker's guarded failure region so failures cannot strand rows in `PROCESSING`.
- For the latent notification/SMS cross-product, either prove it remains unwired or enforce source/review ownership matching before linking. Do not broaden linking behavior.

**Primary surfaces:** notification intake worker/coordinator, review queue repository/DAO, WorkManager scheduling and recovery code.

**Gate:** compile; notification worker/review queue targeted tests; worker/retry/cancellation guard tests; strict worker and privacy review.

### W4-2 — Reminder delivery and recurring reconciliation (CL-12 / CL-13)

**Findings:** `CA-P-04-001`, `CA-P-04-002`, `CA-P-04-003`, `CA-P-04-004`.

**Fix shape:**

- Make snooze/dismiss actions perform their lifecycle effect, including cancelling posted notifications where required.
- Make `FAILED_TRANSIENT` reminders recoverable through the normal selection/claim path with bounded retry semantics.
- Do not materialize deliveries when a recurring rule is inactive or paused.
- Pass the original `anchorDayOfMonth` or `rule.nextDate` through expansion; do not derive the anchor again from a clamped calendar date. Historical persisted drift is out of scope unless the refresh finds a new safety issue.

**Primary surfaces:** reminder action coordinators/workers, notification cancellation, recurring rule reconciliation, projection/expansion/materialization.

**Gate:** compile; reminder, recurring, notification-action, and retry tests; worker/lifecycle guard tests; strict worker and transaction review.

### W4-3 — Dashboard windows and trend semantics (CL-14 / CL-16)

**Findings:** `CA-P-05-001`, `CA-P-05-002`.

**Fix shape:**

- Fetch the complete six-month source range before applying the full week window so Monday-start boundary rows are not removed by a month prefilter.
- Sort trend data chronologically and calculate change as `(last - first) / first` using the intended oldest/newest values.

**Primary surfaces:** dashboard date-window queries, trend calculators, analytics models and tests.

**Gate:** compile; dashboard/trend/analytics targeted tests; money-boundary checks where normalized totals are involved.

### W4-4 — Budget, forecast, and data-quality semantics (CL-28)

**Findings:** `CA-P-06-002`, `CA-E-02-001`, `CA-E-02-003`, `CA-E-02-004`.

**Fix shape:**

- Apply the documented per-budget `±15%` clamp after hierarchy scaling, not only before scaling.
- Compute overall budget spend from all categorized and uncategorized spending rather than `categorySpending[null]` alone.
- Classify missing-rate impact from `affectedTransactionCount`, not from the number of warning objects.
- Include recurring terms in projected trajectories when the synthesis includes committed recurring occurrences.

**Sequencing note:** `CA-E-02-003` is money-quality adjacent. If its implementation touches a file selected for the money migration, complete the semantic fix in the earlier batch and keep the raw-money refactor separate.

**Gate:** compile; budget, forecast, synthesis, dashboard quality, and health tests; strict money/forecast review.

**Implementation note:** W4-4 treats the overall budget as the governing total,
uses the approved money boundary for normalized aggregates without double-counting
category actuals, derives conversion-quality counts from affected rows, and carries
committed recurring terms into the projected timeline. Focused validation and
strict review remain pending.

### W4-5 — Backup, export, and navigation state safety (CL-20 / CL-24 / CL-26 / CL-30)

**Findings:** `CA-P-07-007`, `CA-P-12-002`, `CA-I-05-003`.

**Fix shape:**

- Align resolved backup privacy flags, manifest metadata, and ZIP contents for the default and `REDACT_RAW_TEXT` modes.
- Clear stale completed export results when the format changes, or retain and validate the originating format before save/share.
- Remove financial amount/currency payloads from `rememberSaveable` navigation state while preserving the editor round-trip contract through a non-persistent mechanism.

**Gate:** compile; backup/restore, export format, and split-editor tests; privacy and export strict review; relevant guard suites.

**Implementation note:** W4-5 resolves receipt-image inclusion from the explicit backup privacy mode, fails closed on required asset collection/missing-file errors, invalidates and cancels stale export work on format changes, and keeps visual-split financial fields in memory or reloads them by expense ID instead of persisting them in navigation tokens. Focused validation and strict review remain pending.

### W4-6 — Staged email parser hardening (CL-24 / CL-26)

**Findings:** `CA-P-11-001`, `CA-P-11-002`.

**Fix shape:**

- Make conflicting ISO currency evidence fail closed instead of trusting a domain fallback.
- Require the summary-row predicate for keyword total extraction and bind the amount after a bounded total label so unit-price, savings, and unrelated amounts are not accepted as the receipt total.

**Scope note:** This is a separate low-reachability batch because the service is not currently injected into production. Do not expand its production wiring as part of the fix.

**Gate:** compile; email currency/parser targeted tests; privacy/parser strict review.

### W4-7 — Documentation and policy reconciliation (CL-06)

**Findings:** `CA-P-01-007`, `CA-P-03-005`, `CA-E-05-001`, `CA-E-05-002`, `CA-I-02-001`, `CA-I-03-001`, `CA-I-03-002`.

**Fix shape:**

- Update stale RP markers, legal paths, AppDatabase KDoc, WorkerRunLogger ownership, and the four stale documentation surfaces.
- For `CA-I-02-001`, include the parity/migration test correction if the current guard still anchors the dead callback claim; do not make a docs-only change when the test contract is part of the finding.
- For subscription DAO entries, reconcile documentation to the enforcement policy rather than weakening the policy.

**Dependency:** Run after the affected code batches so docs describe final ownership and lifecycle paths.

**Gate:** documentation truth guards, architecture/static guard tests, strict architecture review.

## Money aggregate track

The money track is a separate workstream and should start with an inventory batch, then move from lower-level producers to consumers. The objective is to route financial aggregation through the existing typed normalization/MoneyAggregate contracts while preserving conversion quality, rounding, partial results, and unavailable-home-currency behavior.

### M-0 — Money contract and finding classification

**Work:**

- Re-read all 83 findings against the current source and classify each as true financial aggregation, typed scalar that needs a better boundary, or a scanner false positive requiring guard-rule review.
- Map every finding to an owning segment and a consumer-facing result type.
- Identify shared adapters/helpers that can reduce repeated edits without creating a new bypass.
- Define expected `MoneyAggregateResult`/unavailable behavior and test fixtures before changing callers.

**Exit:** A file-level inventory with no blanket `Double` replacement and no proposed allowlist expansion.

### M-1 — Receipt and item-value producers

**Primary files:** receipt parsing and AI receipt-item categorization surfaces, including raw price-field aggregation.

**Work:** Replace raw price/amount aggregation only where it represents financial totals; preserve item-level values where they are not aggregate money, but make that boundary explicit and guard-safe. Add mixed-currency, missing-currency, rounding, and empty-input tests.

**Dependency:** M-0.

### M-2 — Analytics and canonical totals

**Primary files:** `TotalsAggregationEngine`, analytics engines/models, daily/monthly comparisons, pace/personality/category insight paths, and their view-model adapters.

**Work:** Route transaction totals through `MoneyNormalizationEngine`/typed aggregates, preserve chronological and partial-quality semantics, and remove raw `amount`, `effectiveAmount`, and `normalizedAmount` sums. Keep UI formatting out of the domain conversion.

**Dependency:** M-1 where receipt-derived values enter analytics; coordinate with W4-3 and W4-4 to avoid editing the same files twice.

### M-3 — Forecast, budget, health, and savings consumers

**Primary files:** forecast input/distribution, financial stress, budget forecasting, synthesis, health score/calculators, smart savings, savings gamification, and monthly sweep use cases.

**Work:** Replace raw totals and `total: Double` aggregate contracts with typed outcomes; preserve `UNAVAILABLE`, `PARTIAL`, rate-basis, and excluded-count semantics. No fabricated home-currency or EUR fallback.

**Dependency:** M-2 and the W4-4 semantic fixes.

### M-4 — Dashboard, cash-flow, and export consumers

**Primary files:** dashboard widget/radar use cases, analytics/dashboard view models, cash-flow calendar, totals cards, and accountant PDF export.

**Work:** Consume typed aggregate results, render unavailable/partial states explicitly, and ensure exports use the same rate basis and quality metadata as on-screen totals.

**Dependency:** M-2 and M-3. Coordinate with W4-5 so export state fixes and money-type changes do not conflict.

### M-5 — Money guard closure

**Work:** Re-run the raw-money and money-boundary guards, inspect every remaining finding, and add focused regression tests for each resolved rule family. Only update guard implementation/docs if the classification proves the scanner is wrong; never suppress valid financial arithmetic.

**Exit:** `raw_money_aggregates` has no unexplained findings, or any remaining finding has an explicit reviewed disposition that is not a baseline/allowlist shortcut.

## Validation cadence

For every implementation batch:

1. Strict review of the batch diff and affected architecture boundary.
2. User-run compile through the validation runner.
3. User-run focused test filters for the changed owners.
4. User-run relevant guard profile(s), including privacy/worker/money guards where applicable.
5. Record the run IDs and fingerprint before starting the next batch.

After W4-7 and M-5:

- Run the full static-guard suite on the final fingerprint.
- Reconcile the Wave 4 finding ledger and money inventory.
- Perform one independent strict review of the complete batch series.
- Keep deferred device gates and any separately accepted debt explicitly outside the completion claim.

## Suggested execution sequence

1. `W4-0` current-source scope refresh.
2. `W4-1` notification reliability.
3. `W4-2` reminders and recurring reconciliation.
4. `W4-3` dashboard windows/trends, then `W4-4` budget/forecast semantics.
5. `W4-5` backup/export/navigation and `W4-6` email parser as separate serial batches.
6. `W4-7` documentation reconciliation.
7. `M-0` money contract classification.
8. `M-1` → `M-2` → `M-3` → `M-4` → `M-5`.

Analysis and review for disjoint batches may be prepared in parallel, but implementation and validation remain serialized because the work shares lifecycle, money, and guard surfaces.
