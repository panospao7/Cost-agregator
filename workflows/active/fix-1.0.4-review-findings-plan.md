# Fix plan: fix-1.0.4-review-findings

Author: orchestrator (under owner-approvals items 86/87/92 + owner disposition 2026-11-01 "do your recommendation").
Status: DRAFT - awaiting owner Gate 1 before any `app/` edit.
Lane: `build/fix-wt`, branch `feature/fix-1.0.4-review-findings`, base `d9c55d4d` (= `origin/feature/ai-evolution`, the exact
reviewed SHA). NOT branched from the dirty main checkout (`044be2c8`, 104 dirty paths).
Evidence base: `build/review-wt/build/full-review-2026-11/` (70 artifacts). This plan does not restate that evidence; it cites IDs.

- Risk tier: **T3** overall (money, privacy, Room, guards).
- Schema: **YES in S5 only** (`REFUND` transaction type). S1-S4 and S6 are schema-free.
- Conventions: `A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`.
- Validation ONLY by `validation-runner`, serially, one queue across all worktrees. Never `./gradlew`.
- Concurrency: 2 agents max, but **one writer per lane** (PARALLEL_LANES:12). The second slot is for the independent
  `test-author` on T3 money/privacy slices (owner item 87) or a read-only guardian.

## Owner dispositions applied (2026-11-01, "do your recommendation")

| # | Question | Decision | Revised from my first suggestion, and why |
|---|---|---|---|
| D1 | P-08-1 / P-08-2 privacy | **Add retention targets + export scrub. Keep `STORE_RAW` as default.** | Unchanged. Dropping `STORE_RAW` changes the product's data model and would break the receipt/OCR and receipt-item features that depend on it; adding the missing targets fixes the actual leak without changing what the app is. |
| D2 | DR-05 refunds | **Defer. Do NOT add a `REFUND` type in this plan.** | **REVISED.** A new transaction type touches the schema (v152 -> v153), the spending predicate, every aggregate, every exporter, `DomainTransactionType.isSpending`, the analytics normalisers, and ~10 guards. That is a multi-lane, high-risk change that does not belong in a 6-issue hardening release. Instead S6 adds a **user-facing stopgap** (see D2b) and the real model change becomes its own future plan. |
| D2b | Stopgap for DR-05 | **Show a warning when a DEPOSIT looks like a refund** (merchant matches a purchase in the last 90 days, same amount) and document the edit/delete workaround. | Cheap, reversible, no schema. |
| D3 | BL-11 fabricated finance | **Gate `carbon`, `price`, `negotiation` in release** via `DEFERRED_FEATURE_IDS`. | Unchanged, and cheaper than I first thought: `DEFERRED_FEATURE_IDS` already exists (`{tax, investment, challenges}`) and `FeatureConfig.availableFeatures` already filters on it, so this is a 1-line set change plus tests. Removing the fabricated data is a product decision that should not block a security release. |
| D4 | Scope of v1 | **6 issues** (2 BLOCKER + 4 CRITICAL), plus the 2 cheap HIGHs below that ride along at near-zero cost. | The money-display family (~23 HIGHs) is deferred to its own plan. Rationale: those are display consistency, not data exposure, and mixing them would triple the diff and make the security fix harder to review. |

## Pre-flight check results (planning stage, already done)

1. Lane created from `d9c55d4d`, clean, `local.properties` copied. `git worktree list` shows it.
2. **BL-01 confirmed still valid at this SHA**: `HistoricalSpendingDistribution.kt:179` substitutes `0.01` for quiet weeks;
   `isUsable` (`:437`) is `qualifyingWeekCount >= 4 && sigma > 0.0` - i.e. **no sigma ceiling**, so a poisoned fit IS used;
   `MonteCarloSpendingSimulator.kt:111` only degrades when `!isUsable`; `MonteCarloForecastCard.kt:151,258` always renders
   P10-P90 with only a colour badge. The 10x claim holds.
3. `DeferredFeatureGate.DEFERRED_FEATURE_IDS = setOf("tax","investment","challenges")` (DeferredFeatureGate.kt:21);
   `FeatureConfig.kt:52,68,76` declare `carbon`, `price`, `negotiation`. D3 is therefore a one-line addition.
4. Shared hot files touched by this plan: `strings.xml` (S1/S6 copy), `cancellation.json` (not expected), guard regeneration
   (S6 only). **One writer program-wide** on each, serialised by slice order.

## Slice table

| ID | Title | Tier | Prod files | Depends on | Reviewers | Key FG | Issues closed |
|---|---|---|---|---|---|---|---|
| S0 | Re-anchor: confirm findings at base, freeze baseline run | - | 0 | lane created | - | - | - |
| S1 | Privacy: retention targets + export scrub | T3 privacy | 4-5 | S0 | PSG + independent test-author | 16, 19, 22 | P-08-1, P-08-2 |
| S2 | Parser: balance stripping + auto-accept boundary | T3 money | 3-4 | S1 | AG + PSG | 10, 16 | FL-02, FL-01 |
| S3 | Forecasting: LogNormal fit + savings units/cap | T3 money | 4-5 | S2 | AG + independent test-author | 17 | BL-01, BL-03 |
| S4 | Release gating: carbon/price/negotiation | T2 | 1-2 | S3 | AG + cheap reviewer | 26 | BL-11 |
| S5 | Refund stopgap (warning + docs, NO schema) | T2 | 2-3 | S4 | AG + cheap reviewer | 16 | DR-05 (partial) |
| S6 | Guard additions + closeout | T3 guards | 3-4 + policy | S5 | AG + PSG + RS final | 23, 26 | I-04-4, I-04-5, I-01-1 |
| Z | Candidate gate | - | 0 | S6 | **reviewer-strict (fresh session)** | 26 | - |

Order: S0 -> S1 -> S2 -> S3 -> S4 -> S5 -> S6 -> Z. Strictly sequential on the shared hot files.
Rationale for order: privacy first (only release-blocking exposure), then the two silent-money bugs, then the free wins.

## Slices

### S0 - Re-anchor (no `app/` edits)
- Goal: prove the findings still exist at `d9c55d4d` before editing, and take a clean baseline.
- Steps: (1) record `git rev-parse HEAD` == `d9c55d4d`. (2) Re-read the 6 severe findings' cited lines and record them in the
  handoff (the review was at this exact SHA, so drift is not expected - this is insurance). (3) Queue ONE baseline
  `targeted-unit-test` for the classes S1-S3 will touch, so a later failure can be attributed.
- Exit: baseline run PASS with a worktree fingerprint recorded.

### S1 - Privacy: retention targets + export scrub (P-08-1, P-08-2)
- Goal: stop raw notification text and receipt line-item text surviving retention and leaking in "anonymised" exports.
- Prod files: `A/di/RetentionModule.kt` (add `user_corrections` target + extend the `scanned_receipts.rawOcrText` target to
  `receipt_item_categorizations`), `A/data/privacy/ExportAnonymizer.kt` (null `user_corrections.notificationTitle/Text`,
  `receipt_item_categorizations.itemDescription/aiRationale/suggestedCategoryName`),
  `A/data/repository/ReviewQueueRepository.kt` (set `createdAt` at the 3 write sites so a time-based purge has a key - **no
  behaviour change beyond populating a column that is currently always 0**).
- NOT in scope: changing the `STORE_RAW` default (D1), deleting `user_corrections` rows (it is a learning feature).
- Tests (T): extend `RetentionTargetCoverageGapTest`, `ExportAnonymizerTest`, and add a purge test asserting the new target
  nulls the columns past the window. **Independent test-author** writes these (owner item 87, privacy slice).
- Reviewers: privacy-security-guardian (fail-closed, no raw text retained) + independent test-author.
- Exit: targeted tests PASS + `db_access`/`privacy` area guards PASS.

### S2 - Parser: balance stripping + auto-accept boundary (FL-02, FL-01)
- Goal: a bank SMS must not auto-create an expense for the account balance, and clipboard prefill must not take the balance.
- Prod files: `A/domain/parser/parsers/SmsParser.kt` (strip/ignore balance clauses; require purchase-keyword adjacency; when
  more than one currency amount is present, drop confidence below `AUTO_ACCEPT_THRESHOLD`),
  `A/ui/util/ClipboardAmountParser.kt` (reject multi-number and balance-keyword text).
- Constraint: do NOT move `AUTO_ACCEPT_THRESHOLD` itself - that is a product tuning decision. Fix the producer's confidence.
- Tests (T): `SmsParserTest` cases for balance-first SMS, multi-amount SMS, and single-purchase SMS (must still auto-accept);
  `ClipboardAmountParserTest` for the balance SMS and a normal order SMS.
- Reviewers: architecture-guardian + privacy-security-guardian (text handling).
- Exit: targeted tests PASS; no change to unrelated parser fixtures.

### S3 - Forecasting: LogNormal fit + savings units/cap (BL-01, BL-03)
- Goal: stop the Monte Carlo overstating P90 ~10x, and stop the savings engine comparing a discretionary budget to total spend.
- Prod files: `A/domain/forecasting/HistoricalSpendingDistribution.kt` (fit on positive weeks only, or model zero separately;
  add an explicit sigma ceiling in `isUsable`), `A/domain/forecasting/MonteCarloSpendingSimulator.kt` (respect the new guard),
  `A/domain/savings/SmartSavingsEngine.kt` (compare like with like; scale the dynamic cap by transaction count, not one P90).
- Tests (T): hand-derived fixtures - 6 weeks at 100 + 2 at 0 must yield P90 in the ~250-450 band, not ~4700; a rent+dining
  user must not get a zero or 25.00 recommendation. **Independent test-author** (money slice).
- Reviewers: architecture-guardian; `reviewer-strict` is NOT used per-slice (owner item 86) - it runs at Z.
- Exit: targeted tests PASS; `money` + `time_boundaries` area guards PASS.

### S4 - Release gating (BL-11)
- Goal: carbon, price protection and bill negotiation must not be visible in a release build while they contain fabricated data.
- Prod files: `A/ui/navigation/DeferredFeatureGate.kt` (add the three ids to `DEFERRED_FEATURE_IDS` and their save tokens to
  `DEFERRED_SAVE_TOKENS`), plus the destination mapping if a token is missing.
- Tests (T): a gates test asserting all three are withheld when `deferredAvailable = false` and available in debug.
- Reviewers: architecture-guardian + cheap reviewer.
- Exit: targeted tests PASS; nav/deferred gate test PASS.

### S5 - Refund stopgap (DR-05 partial, NO schema)
- Goal: make the refund gap visible instead of silent, without a schema change (D2).
- Prod files: a reviewer-queue / deposit-detail warning when a DEPOSIT matches a PURCHASE in the last 90 days by amount and
  merchant; a docs note in `LEGAL_PATHS.md` recording that refunds are modelled as DEPOSIT in 1.0.x and the edit/delete
  workaround. `strings.xml` additions (HOT FILE - one writer, this slice owns it).
- NOT in scope: the `REFUND` type, the spending predicate, the exporters, the aggregates.
- Tests: a unit test for the match heuristic.
- Reviewers: architecture-guardian + cheap reviewer.
- Exit: targeted tests PASS; `strings.xml` parity guard PASS.

### S6 - Guard additions + closeout (I-04-4, I-04-5, I-01-1)
- Goal: make the three guard gaps real, so the defects they missed cannot silently return.
- **This slice is a guard change: it is a Stop-and-ask item in AGENTS.md. It does not start without explicit owner approval.**
- Prod files: `scripts/verify_pii_logging_boundaries.py` (also flag `e.message` passed into event constructors),
  `scripts/verify_spending_predicate_usage.py` (also cover non-aggregate SELECTs over `expenses`),
  `scripts/db_guard/policy_v2_evidence.py` + `scanner.py` (prove dominance for `helper`/`workerMediated` instead of skipping).
- Policy/baseline edits are guard regeneration (HOT FILE, one writer program-wide).
- Tests: guard fixtures - a seeded violation must exit 1, a clean tree must still exit 0. Additive only; **no baseline growth,
  no allowlist broadening** (FG-06/FG-07).
- Reviewers: architecture-guardian + privacy-security-guardian.
- Exit: `static-guards` PASS with the new rules active and the seeded fixtures failing as expected.

### Z - Candidate gate
- One serial ladder on the final candidate: targeted -> shard -> area guards -> `static-guards` -> `app-check`.
- `reviewer-strict` on the full diff, **fresh session**, at the merge candidate only (owner item 86).
- Then hand back for the owner's merge decision. No push without an explicit owner request.

## Guard/policy edit assignment (hot files, one writer)
| File | Slice | Note |
|---|---|---|
| `app/src/main/res/values/strings.xml` (+ el/es) | S5 | S1 may add retention strings; S1 owns first, then S5 |
| `docs/architecture/LEGAL_PATHS.md` | S5 | refund modelling note |
| `scripts/verify_*.py`, `config/guards/`, `config/baselines/` | **S6 only** | guard regeneration, needs owner approval |
| `TransactionLifecycleCoordinator.kt` | **not touched** | avoids the single hottest file entirely |

## Validation plan (batched, cheapest first)
- After S1+S2+S3 (one batch): one combined `targeted-unit-test` filter covering every changed test class, then the `data` and
  `domain-*` shards, then `privacy`/`money`/`db_access` area guards.
- S4+S5: targeted only.
- S6: `static-guards` (required).
- Z: full ladder once.

## Explicitly OUT of scope for this plan
- The ~23-row money-display family (N-08-*, P-05-*, P-06-*, E-01-4). Own plan later; E-01-4 is a 1-line fix and should be the
  first slice of it.
- G-LOCK-1 (recorded HIGH; owner declined BLOCKER reclassification). Needs its own decision.
- Everything debug-gated (statement import, bank sync, email ingestion).
- Any refactor, rename, or reformat. Minimal diffs only.

## Stop and ask before
- Starting S6 (guard change).
- Changing `AUTO_ACCEPT_THRESHOLD` or any product-tuning constant.
- Adding a Room entity/column/version (S5 is deliberately schema-free for this reason).
- Touching `TransactionLifecycleCoordinator`.
