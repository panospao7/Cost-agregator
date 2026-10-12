# Wave 5 connected failure repair - 2026-10-12

Status: PARTIAL - C4/C5 connected run finished with 4 failures, 341 passes and 9
skips (354 XML cases). All four remaining failures are archived category-index
parity failures. User now authorized a WIP commit/push for cross-PC continuation;
see money-w5-cross-pc-20261012-handoff.md. Latest terminal full-suite result is FAIL.
Earlier sections retain checkpoint history; the latest sections below supersede
their pending-validation and approval notes. Do not claim whole-wave acceptance.

## Authority / scope

- Resume money-w5-pdf-b7-20261012-handoff.md, not prior chat. Last connected run
  vr-20261012-070252-55901f2e: FAIL/1, 350 XML testcases, 43 failures / 9 skipped.
- User's "resolve them" approves narrow failure repairs, not destructive reset,
  guard weakening or wholesale retirement of historical tests. No-further-subagents
  amendment remains active: named scout/planner/coder/reviewer/validation-runner
  responsibilities handled locally; independent acceptance remains NOT RUN.
- Mixed tree 153 tracked modified / 55 untracked, no staged paths. Preserve all
  prior changes. No live validation lock at discovery.
- Strict mode; read scoped architecture segments/inventory, export/group legal
  paths, engine impacts, real entities/DAOs/builder and persisted failure evidence.

## Batch C1: current-contract fixtures (approved intent, source-verified)

1. PDF test: remove platform Context/static FileProvider mocks; use the real
   application context and narrowly cleaned synthetic files within the declared
   FileProvider export root. Preserve success/count/header/rasterization and add
   actual provider readability evidence. Do not change the exporter/repository.
2. Schema 147: repair BOM-less UTF-16LE JSON to UTF-8 without changing decoded
   JSON, identity hash, fields or schema. Snapshot encoding only, not a schema
   version change; no DB version bump/migration needed for this byte repair.
3. Matrix: supply DatabaseSchemaPolicy.ALL_MIGRATIONS to full supported-chain
   tests; use policy version constants; fix fixture SQL referencing a nonexistent
   effectiveAmount storage column. Preserve full Room schema validation and seed
   preservation. Replace the false destructive-fallback expectation with canonical
   builder refusal plus read-only proof the unsupported DB's row/version survived.
   This repairs a policy contract, not a DB reset or retirement of the test.
4. 148-to-149 contract fixture: create/seed background_job_runs as well as bank
   review prerequisite tables for the merged migration; assert counter defaults
   and pre-existing row preservation alongside original bank identity guarantees.
5. Fresh-install tests: retain exact index sets, include declared composite
   indexes, assert duplicate rejection/row preservation for canonical Room
   uniqueness, and use accepted action for a converted subscription fixture.
   Keep null group-expense links allowed. No entity/index/DAO policy changes.

## Gates / stop conditions

- Local strict source review before validation; independent review NOT RUN.
- No Gradle/connected/guard execution except through validation-runner.ps1.
  Current connected profile has no class filter and runs the full suite (~40m).
  Ask before an expensive rerun; no automatic suite launch while editing.
- Stop for unclear production/schema changes. No skip additions, deleted tests,
  schema shortcuts, destructive fallback, new allowlists or baseline ratchets.
- Batch C1 addresses 13 current database fixture failures, the PDF crash and the
  matrix's incorrect pre-baseline fallback contract. Remaining 28 pre-v145 tests
  require a separately verified historical-coverage
  decision/repair batch. Their archived migrations exist but are intentionally
  excluded from the production registry. Do not broaden production support or
  retire those tests silently to obtain green.
- Savings Batch 8 implementation and public baseline reset remain separate.

## Validation continuation approval

User: "yes do until we fix them all where possible". This authorizes the connected
rerun and subsequent narrow, source-reviewed repair/rerun iterations. Earlier
ask-before-rerun notes below describe the pre-approval checkpoint. Keep runs
serial through the wrapper; hold the fingerprinted tree fixed during every run.
No destructive reset, constraint weakening, skips/coverage retirement, expanded
production migration support or unrelated Batch 8 work authorized. Read-only
diagnosis may continue during a run; persist/edit only after terminal evidence.

## Files touched in C1

- app/src/androidTest/java/com/yourname/expensetracker/data/repository/AccountingExportPdfRepositoryInstrumentedTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationMatrixTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/Migration148to149ContractTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/FreshInstallIndexParityTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/GroupMemberDaoTest.kt
- app/schemas/com.yourname.expensetracker.data.database.AppDatabase/147.json
- This packet, money-w5-pdf-b7-20261012-handoff.md and money-w5-candidate-paths-20261008.md.

## Implemented / local strict source review

VERDICT: PASS for the narrow C1 source slice. Issues: None.

- PDF uses the real application Context and manifest-configured FileProvider.
  Native platform class/static mocking removed; app-owned collaborators remain
  mocked. Preserve all prior success/count/path/header/page-rendering assertions;
  add real contentResolver readability/byte equality. The synthetic output/temp
  names are checked absent before writing and narrowly removed in finally. No
  recursive cleanup, provider-root broadening or production exporter edits.
- Schema 147 was BOM-less UTF-16LE. Converted only its encoding to BOM-less UTF-8;
  decoded text equals HEAD exactly (including line endings), identity hash
  cdc3672db689360497d909af3996202e, 70 entities. SHA256 after conversion:
  776bd2647a8bf41ca393538172a5350bff5e82466140b07bdd602c64bf149cd5.
  No semantic schema change, database version bump or new migration implied.
- Supported matrix tests now require their schema assets (assert, not assume),
  supply the real production migration array and retain validateDroppedTables=true.
  New regression requires strict UTF-8 decoding/BOM-free JSON/no UTF-16 NULs for
  supported snapshots. Seed expense survival asserted in single/full-chain cases;
  fresh-side parity uses the canonical Room builder, not another snapshot-created DB.
- Unsupported-version matrix test is retained with corrected fail-closed policy:
  canonical builder must refuse the old DB, then read-only SQLite verifies old
  version/row/currency/amount survived. No destructive fallback or legacy-chain
  registration added. Its old "destroy or preserve is acceptable" contract was
  inconsistent with production and offered no actual preservation guarantee.
- Merged 148-to-149 fixture creates parent tables and a synthetic worker-run row,
  enables FK enforcement and asserts new counters default to zero while preserving
  the existing row. Original bank identity/unique-index/null-identity assertions kept.
- Fresh-install index sets remain exact, including Room's forecast and subscription
  composite indexes. Duplicate merchant/action and expense links must fail with
  SQLiteConstraintException and leave the first row unchanged. Converted candidate
  action matches markAsConverted (accepted); pending/converted coexistence retained.
  Forecasts use distinct deterministic instants, plus a same-instant rejection/
  financial-history preservation assertion. Null standalone group links remain allowed.
- Read entities/DAOs/canonical builder and scoped export/group/rescue legal paths;
  no new lifecycle bypass or production data access. All fixtures are synthetic.
  No raw payload/stack trace/SQL exception message/new diagnostics persisted.
- Test method counts: matrix 7->8; migration contract 2->2; index parity 5->5;
  group member 16->16; PDF retains both existing Batch 7 tests (2). No @Ignore,
  new assumptions or removed tests. One regression added; previous native suite's
  350 testcase count is not evidence for the changed suite.
- Local review is not independent acceptance. Privacy/Room source checks local;
  independent guardian/reviewer gates NOT RUN under the no-subagents amendment.

## Validation / next checkpoint

- Scoped git diff --check: PASS (exit 0).
- Read-only encoding comparison: decoded schema text matches HEAD exactly.
  This is format/diff inspection, not a build, Room migration test or guard run.
- Compile/tests/connected/static guards after C1 edits: NOT RUN. No fresh run ID
  or passing completion marker. Do not reuse the prior connected/JVM passes as
  acceptance of these fixture edits.
- Next validation command, only after expensive-run approval: powershell -NoProfile
  -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start
  -Profile connected-tests. Current profile runs the whole native suite (~40m)
  and ignores TestFilter; do not disguise it as a targeted PDF invocation.
- Freeze source/docs during the run; poll RUNNING, never rerun. Inspect result,
  physical marker, equal fingerprints and fresh testcase XML. Extract C1 classes
  separately even if untouched legacy tests keep the broad suite red. Fix any
  newly exposed supported schema drift rather than assuming fixture repairs passed.
- 28 other pre-v145 failing tests from the last report are untouched. They test
  archived migration bodies/chains and need a separate verified batch; no coverage
  retirement or expanded production migration support authorized by this packet.
- Mixed tree now 158 tracked modified / 56 untracked before final documentation;
  nothing staged/committed. No production Room/entity/migration/DAO edits in C1;
  APP_DATABASE_SCHEMA_VERSION remains 149, baseline remains 145. No data reset.

## Batch C2 terminal evidence

- Run `vr-20261012-112415-74076ead`: terminal `FAIL`, exit code 1, source
  fingerprint equal at start/end, completion marker present.
- Fresh connected XML: 351 cases, 19 failures, 9 skips, 323 passes. The C1
  target classes passed; all remaining failures were archived migration-chain
  or migration-contract cases.
- Failure groups: pending-review index uniqueness drift through v76 (13 cases),
  duplicate `parseResult` addition in 103→104 (3 cases), group-expenses FK
  drift in 50→51 (1 case), return-window metadata drift in 113→114 (1 case),
  and the incomplete v72 correction-table fixture (1 case).

## Batch C3: archived migration parity repairs

Implemented and source-reviewed locally before the next validation run:

- `AppDatabase.kt`: keep the pending-review raw-notification index non-unique
  through v76; preserve the v82 uniqueness transition; align the v50 and v51
  group-expenses column/FK contracts; make 103→104 add only its two newly
  introduced retention columns; add the v113→114 refund FK/index; preserve
  historical v50 index timing/defaults and bank rows. The active v145→149
  registry and baseline remain unchanged.
- `MigrationContractTest.kt`: add the full v72 correction-table fixture,
  normalization collision assertions, unique-index assertion, and duplicate
  rejection/data-preservation checks.
- `DatabaseMigrationTest.kt`: add retention-column/data-preservation coverage,
  refund FK/index and cleanup coverage, and v50→51 payer RESTRICT/data-preservation
  coverage. No tests were skipped, removed, or weakened.

### Local strict review for C3

VERDICT: PASS for the C3 source slice. Issues: None.

- Scoped Room snapshot comparison reports no column/index mismatches for the
  repaired 49→50, 50→51, and 75→76 rebuilds.
- Read-only delimiter inspection found no imbalance in the three changed Kotlin
  files; it is not compiler evidence. `git diff --check` passes for the scoped files.
- The edits are limited to archived migration bodies and synthetic migration
  fixtures. No active production migration registry, schema version, baseline,
  lifecycle path, guard, allowlist, or privacy behavior changed.
- Independent reviewer/guardian remains NOT RUN under the no-subagents amendment.

## C3 validation checkpoint

The tree is now frozen for the next serialized `connected-tests` run. Inspect the
terminal result, completion marker, equal fingerprints, and fresh XML before any
further edits. Do not claim completion if repeated historical drift remains.

## C3 terminal evidence

- Run vr-20261012-124244-0c0c6ef8: FAIL/1, completion marker present, 3209 seconds,
  finished 2026-10-12T13:36:21Z. Fingerprints start/end both
  edf6aaa9543c6c6d64e7fe4f30b5e23283cd16f63b57d4da5f36bbbfdfe0f062.
- Fresh XML: 352 cases, 8 failures, 9 skips, 335 passes. Console totals exceed
  XML totals; use XML evidence. All failures are DatabaseMigrationTest cases.
- Four failures: subscription_price_history.currency lacks its v50 default.
  Three: long chains lack the expense baseAmount/baseCurrency/exchangeRateUsed
  fields introduced in v94. One: v114 lacks its forecast-date unique index.
- The new 103-to-104 preservation test and all MigrationContractTest cases pass.

## C4/C5 scope, implementation and review

Files touched:
- app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt
- This handoff.

What changed:
- v49-to-50: rebuild subscription_price_history, exchange_rates and budget_forecasts
  to apply the three missing snapshot defaults, with explicit copy columns that
  retain all existing values. Original FK-off transaction/restore remains in place.
- v93-to-94: complete all 18 added currency/conversion/date columns and the
  non-unique exchange-date index from the v94 snapshot. Preserve original amounts
  and currencies; no fabricated historical currency conversions.
- v113-to-114: create the forecast-date unique index at its exported introduction.
  The later IF NOT EXISTS remains idempotent; no schema snapshot edited.
- On the same failing long chain, source inspection verified further mismatches:
  align new lifecycle-table defaults (95-to-96 and 96-to-100), nullable-key defaults
  (104-to-105), and background-run defaults/index names (105-to-106).
- v106-to-107: retain budget/member invariant keys, planned-expense currency and
  assumption fields, and occurrence keys instead of copying NULL. Keep existing
  CHECK constraints and all stored financial rows.
- Remove fetchedAt additions only from 106-to-112 because those snapshots never
  declare it; remove extractionState additions before its v110 introduction.
- Align refundCurrency default (108-to-109) and budget.createdAt default (111-to-112).
  Complete v112 receipt defaults by rebuilding/copying every field under the existing
  FK-off transaction, restore all four receipt indexes and the unique return-receipt
  index. Add manual-recurring categoryId at its v113 introduction.
- Tests: extend 49-to-50 with USD subscription preservation/default inserts, rate
  source preservation/default, inactive-forecast preservation/default. Add v93-to-94
  exact Room validation and seeded amount/currency/assumption/nullability/FK checks.
  Extend v113-to-114 with a duplicate instant under a distinct target period and
  financial-history preservation. Add a seeded 106-to-107-to-114 test requiring
  explicit USD/GBP currencies, active/member/occurrence keys, receipt text/amount/
  currency/timestamps and warranty/return links to survive; retain FK validation.

Local strict source review:
VERDICT: PASS for the C4/C5 slice. Issues: None.
- Read-only comparison: every repaired CREATE TABLE column/nullability/default
  matches its target export. v94's 18 additions match exactly. All newly copied
  table rows use explicit columns. Review checked target index names/version timing.
- Scoped git diff --check exit 0; delimiter inspection balanced, not compile evidence.
- Active DatabaseMigrations.ALL remains 145-to-149 only; version149/baseline145
  unchanged. This is archived reference coverage, no production support expansion,
  snapshot alteration, destructive fallback/reset, guard/baseline change or new skips.
- No diagnostics/logging additions or real user payloads in fixtures.
- Independent review/guardian NOT RUN under the no-subagents amendment.

Validation at the pre-run checkpoint (superseded by terminal evidence below):
- C4/C5 compilation/connected outcome was NOT RUN at this checkpoint.
- Planned command: powershell.exe -NoProfile -ExecutionPolicy Bypass -File
  scripts/validation-runner.ps1 -Action Start -Profile connected-tests.
- Freeze source/docs until terminal evidence. Poll RUNNING, never start an overlap.
  Inspect result, physical marker, equal fingerprints and fresh XML before edits.

Risks/follow-up:
- The last full suite remains FAIL; historical parity may uncover more defects.
  Run expected 354 XML cases (two new regressions) with existing nine skips retained.
- Independent acceptance and unrelated Wave5 savings Batch8 remain outside this
  repair checkpoint. The earlier no-commit/stage/push boundary is superseded only
  for the user-authorized WIP transfer described below; unrelated work stays local.

## C4/C5 terminal evidence and WIP transfer - 2026-10-12

- Run `vr-20261012-142426-b8ed659c`, profile `connected-tests`: FAIL, exit 1,
  `E_COMMAND_FAILED`. Finished 2026-10-12T15:23:10.7005671Z; elapsed 3521 seconds.
- Actual serialized command: `gradlew.bat :app:connectedDebugAndroidTest
  --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1
  -PvalidationForkEvery=50 --no-daemon`, executed through validation-runner.
- Start/end fingerprints both
  `11ad8c7ef890c387e24501e90bc25987100b29840120512282b40b1179615204`.
  Base revision: `a1c66173219d7d959b0d36859a68176aaf814242`.
- Durable result, stdout/stderr logs and physical FAIL completion marker inspected
  under `build/validation-runs/vr-20261012-142426-b8ed659c/`. No active lock remains.
- Fresh connected XML reports 354 cases: 341 passed, 4 failed, 9 skipped. Use XML
  rather than the inconsistent progress-console totals. App/test compilation
  completed within this run; this is not full unit/static-guard PASS evidence.
- Remaining `DatabaseMigrationTest` failures:
  - `migrate_92_to_114_chain_passes`
  - `migrate_106_to_114_preserves_materialized_keys_currency_and_receipt_history`
  - `migrate_all_versions_from_33_to_114`
  - `migrate_92_to_115_chain_passes`
- All fail Room validation of `categories`: archived 112-to-113 creates UNIQUE
  `index_categories_name_nocase`, while target 114/115 exports expect no such
  index. Reconcile the historical contract before choosing a repair; do not
  simply drop uniqueness, alter snapshots, skip tests or relax Room validation.
- The seeded 106-to-107 checkpoint passes, but that test's post-114 preservation
  assertions remain blocked by the category mismatch; do not claim end-to-end
  preservation evidence for it.
- User explicitly requested commit/push to continue Wave 5 on another PC. This
  authorizes a selected WIP checkpoint, not release acceptance or guard waivers.
  No further application edits or validation runs are part of the transfer.
- Cross-PC packet and candidate notes are updated after the terminal run, so its
  fingerprint is historical for the committed tree. Application candidate byte
  hashes were rechecked unchanged before documentation edits. Independent review
  remains NOT RUN; broad final unit/static-guard gates remain pending.
