# Wave 5 Batch 7 PDF policy and device evidence - 2026-10-12

Status: PARTIAL - prior compile and 32 focused JVM tests passed; local strict
source review passed. User-approved JUnit import and orchestrator dependency
repairs resolved compilation/startup. Connected run vr-20261012-070252-55901f2e
finished FAIL/1: XML reports 350 tests, 298 passed, 43 failed and 9 skipped.
The direct mixed-currency native PDF rendering test passed; repository PDF test
crashed in MockK native instrumentation. Native repository acceptance, independent
and whole-wave acceptance remain pending. Validation is stopped, emulator remains
online. No release-baseline reset, migration retirement or test removal implemented.

Subsequent user-requested fixture repairs are recorded in
money-w5-connected-repair-20261012-handoff.md (Batch C1). New source review passed
locally; fresh connected validation NOT RUN. The results below are pre-C1 history.

## Authority and scope

- Approved remaining plan: money-w5-remaining-implementation-plan-20261009.md,
  Batch 7 (R11/R20). User requested proceeding after Batch 6.
- Previous packet: money-w5-source-b6-20261011-handoff.md. Its 269 tests are
  historical to that slice/fingerprint, not current evidence after this edit.
- No-further-subagents user amendment remains active: discovery, implementation,
  strict source review and wrapper validation are local; independent acceptance
  remains outstanding.
- Branch bug-fixes; HEAD a1c66173219d7d959b0d36859a68176aaf814242.
  Starting mixed tree: 150 tracked modified / 54 untracked paths. Preserve prior
  Wave 5 and unrelated hunks; nothing staged/committed.
- Read Segment 18, scoped inventory, Accounting Export and Money/Currency legal
  paths, export engine impact, real exporter, repository caller and PDF tests.
- No Batch 8 savings persistence implementation, Room/schema/migration, guard,
  runner, baseline or allowlist edits. R13 ratchet remains unauthorized.

## Files touched

- app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt
- app/src/test/java/com/yourname/expensetracker/domain/export/AccountantReportPdfMoneyTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/repository/AccountingExportPdfRepositoryInstrumentedTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/FreshInstallBatch8ParityTest.kt
  (separately approved missing JUnit fail import only).
- app/build.gradle.kts (separately approved androidTestUtil dependency only).
- This packet and money-w5-candidate-paths-20261008.md.

## What changed / verified contracts

- Existing typed source sections feed the real PDF renderer: totals/category
  counts use source currency and effective ownership-adjusted amounts. Source
  sections never convert or fabricate a combined cross-currency total.
- Deductible valuation retains RateBasis.LATEST_AVAILABLE with BucketDatePolicy.Latest;
  historical expense dates are not substituted into this compatibility policy.
- Narrow discovered gap: deductible grouping uppercased raw labels but did not
  trim them, allowing USD and whitespace-USD to produce duplicate same-currency
  FX buckets. Reuse the existing reportCurrencyCode() normalization before
  grouping. One production line changed in this continuation; existing money
  totals/counts, support checks and rate policies are retained.
- Preserve all six existing direct PDF-money tests and add ten tests: canonical
  buckets/conversion count, EUR/USD/GBP shared/not-mine deductible inputs, ignored
  non-business input, invalid empty target, invalid source before any conversion,
  unchanged cancellation, category-name aliases, null/unmapped categories,
  retained zero/count rows, unsupported public-export input before Android PDF
  allocation, non-finite input rejection and transaction-selection boundaries.
- PDF input contract is List<Expense> plus category names, not receipt line items.
  Its unassigned expense rows remain Uncategorized and reconcile amounts/counts.
  Do not invent receipt-item ingestion or duplicate item-allocation business rules
  in this exporter. Separate ReceiptTaxAllocator/SourceCurrencyAllocation filters
  establish their own item/unallocated arithmetic, not native PDF rendering.
- Extend the existing real Android repository/PDF test without removing success,
  row count, path, nonempty or %PDF assertions. PdfRenderer parses/rasterizes all
  pages and verifies visible pixels; a new synthetic mixed-currency multipage
  test checks actual native serialization/rendering and zero FX calls.
- Native test fixtures are synthetic and temporary; descriptors/pages/renderers
  close and bitmaps recycle. No screenshot, raw receipt text, exception payload
  or new diagnostic persistence is introduced.

## Local strict source review

VERDICT: PASS for this narrowed authored slice. Issues: None.

Checked current diff and surrounding source/caller: supported currency gates,
canonical grouping, effective amounts/counts, source sections actually consumed
by export, typed partial/unavailable deductible semantics, cancellation, pre-PDF
failure boundary, no new data mutation, existing privacy/read-barrier caller,
and resource lifetimes. This is source review, not independent guardian sign-off.
The broader repository privacy/diagnostic state is not declared closed here.

## Validation

The pre-device checkpoints below and earlier retries are historical. The current
terminal connected evidence and remaining failures are recorded at the end.

- Scoped git diff --check: PASS.
- Compile and four focused unit filters: PASS/0, 32 tests, with fresh terminal
  evidence below. No failed or skipped tests; this is not a full-suite run.
- Android test changes: authored/source-reviewed only; NOT COMPILED and NOT RUN
  by those main/JVM profiles. No attached target; emulator -list-avds reports an
  installed Pixel_8a AVD. It was not booted. Connected-tests is a separate,
  potentially expensive native PDF release gate; the current wrapper profile
  runs the broad connected suite, not just this PDF class. Ask for approval
  before booting/running it. Do not substitute portable unit
  routing tests or a mocked PDF header for device proof.
- Independent strict/privacy/architecture gates, full suite/shards and static
  guards: NOT RUN. Ask before expensive broader/device validation.
- No live runner lock or competing validation found before the queue. Freeze
  this packet/source/tests before running; poll RUNNING, never restart it. PASS
  requires result PASS/0, physical completion marker and equal byte fingerprints.

## Follow-up

1. Local named compile/unit gates are historical passes. Direct native multipage
   rendering passed, but repository PDF acceptance remains pending after a crash.
2. Scope a narrow instrumented fixture repair for the MockK native agent abort,
   preserving the real repository/PDF assertions. Exact mock target is not yet
   confirmed. No automatic dependency upgrade, assertion weakening or rerun.
3. Reconcile supported-version and fresh-install database failures separately from
   obsolete prototype migration coverage. A first-release baseline needs an explicit
   data-preservation/import plan; the user's discussion did not authorize a reset.
   Batch 8's durable savings architecture gate remains unstarted; finalize any
   approved remaining schema changes before freezing the public release baseline.
   Do not treat existing Room + DataStore dual writes as atomic.
4. No commits, staging, baseline ratchet or whole-wave DONE/commit-ready claim.

## Fresh local terminal evidence

LOCAL VALIDATION VERDICT: PASS for compile and the four named focused unit filters.
LOCAL STRICT SOURCE REVIEW VERDICT: PASS for the authored slice; issues: None.
Native Android PDF test compilation/execution: NOT RUN. Independent review: NOT RUN.

All runs were serialized through scripts/validation-runner.ps1:
- powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
- Same wrapper with -Profile targeted-unit-test -TestFilter '<filter below>'.
- Poll each live run with -Action Wait; no RUNNING restart or parallel validation.

| Profile/filter | Run ID | Tests passed | Result / exit |
|---|---|---:|---|
| `compile` | `vr-20261011-213504-84240112` | - | PASS / 0 |
| `*AccountantReportPdfMoneyTest*` | `vr-20261011-214337-fbd8d850` | 16 | PASS / 0 |
| `*AccountingExportPdfRepositoryTest*` | `vr-20261011-215349-c034edc7` | 1 | PASS / 0 |
| `*ReceiptTaxAllocatorTest*` | `vr-20261011-215536-538ba88b` | 4 | PASS / 0 |
| `*SourceCurrencyAllocationTest*` | `vr-20261011-215652-cf490919` | 11 | PASS / 0 |

Total: five terminal records (one compile + four focused filters), 32 tests passed,
0 failed, 0 skipped. Every result.json and complete.marker was physically read.
Each marker contained VALIDATION_COMPLETE, status=PASS and exit_code=0. All result
start/end fingerprints and the current fingerprint matched before this doc append:
4b9487d519a17c080cd9e976a6ddc3383ab32b5046fb1f8e0b742f9c26497536.

Durable per-row paths:
- build/validation-runs/<run-id>/result.json
- build/validation-runs/<run-id>/stdout.log
- build/validation-runs/<run-id>/stderr.log
- build/validation-runs/<run-id>/complete.marker

The sixteen PDF-money tests include all six existing fixtures plus all ten added
cases. The routing test proves forwarding/output-byte preservation, not real PDF
validity. Tax/item source allocation tests prove their separate arithmetic.
No PDF serialization/rasterization pass is inferred from these JVM tests.

After the queue, adb devices -l still reported no attached target, and emulator
-list-avds reported Pixel_8a. No emulator was launched and no connected test was
run; no native-test compilation result exists. Await human approval before the
potentially expensive broad connected-tests profile. Do not add a runner shortcut,
claim a class-filtered native run without runner support, or fabricate native proof.

This evidence/manifest append changes the overall fingerprint; the records are
explicitly pre-doc-append local evidence, not a final whole-wave freeze. The three
slice source/test SHA-256 values are checked unchanged after the documentation
append. No source/test changes followed the successful queue, and no validation
is left running. Nothing staged/committed; no new schema/guard/baseline/runner edits.

Remaining gates: actual native PDF serialization/rendering; independent review;
full suite/shards/static guards; Batch 8 architecture-gated durable savings commit;
R13 ratchet authority and R12 whole-candidate freeze/acceptance.

## Approved connected attempt - 2026-10-12

User approved starting Pixel_8a and the full connected suite. The emulator booted
as emulator-5554 (sys.boot_completed=1 / dev.bootcomplete=1). The test process was
serialized through scripts/validation-runner.ps1 -Action Start -Profile connected-tests.

- Run ID: vr-20261012-061140-9032e23b.
- Command: gradlew.bat :app:connectedDebugAndroidTest --console=plain --no-parallel
  --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon
  (executed only by the wrapper).
- Terminal result: FAIL / exit 1 / E_COMMAND_FAILED, elapsed 666 seconds.
- Result/log/marker directory: build/validation-runs/vr-20261012-061140-9032e23b/.
- Physical stdout.log, stderr.log and complete.marker inspected; marker reads
  VALIDATION_COMPLETE, status=FAIL, exit_code=1. Start/end fingerprint equal:
  4c6ad72272363f486c620828862d11f8c54cca5d24e50813b9d6b81e0c27e6e1.
- First meaningful failure: :app:compileDebugAndroidTestKotlin unresolved reference
  fail at FreshInstallBatch8ParityTest.kt lines 144 and 347. The test imports
  assertNull/assertTrue but not org.junit.Assert.fail. Both missing-import call
  sites are present in HEAD, and the file has no working-tree modifications.
- Native PDF gate: NOT EXECUTED; Android test compilation failed before device
  tests. This is not a PDF behavior failure and not native acceptance.
- No active validation lock remains. adb devices -l reports emulator-5554 online;
  do not imply the emulator itself was shut down. No automatic rerun started.
- No source/test fix applied after the failure: blocker is outside the Batch 7
  write list. Minimal next repair is the single missing JUnit fail import, followed
  by local review and approved wrapper rerun; no assertion weakening/schema edits.

Earlier NOT RUN/no-emulator notes describe the pre-approval checkpoint, not the
current state. This evidence append changes the overall fingerprint again.

## User-approved import repair and retry

User approved adding the missing import and retrying the full connected suite.
- Added only org.junit.Assert.fail to FreshInstallBatch8ParityTest.kt. Neither
  assertion, exception catch, database contract nor test selection changed.
- This is a compile repair for an existing test, not Batch 8 savings implementation.
- Local source review: PASS; one-import test-only diff, no issues. Independent
  review remains outstanding under the local-only user amendment.
- Retry through the serialized connected-tests wrapper profile is pending. Keep
  this packet frozen during the live run; no native PDF PASS until actual execution.

## Import-fixed connected retry - 2026-10-12

- Run ID: vr-20261012-064543-2da21b78; profile connected-tests via the same wrapper.
- Terminal: FAIL / exit 1 / E_COMMAND_FAILED, elapsed 199 seconds. Physical result,
  stdout/stderr and complete.marker verified. Marker: VALIDATION_COMPLETE,
  status=FAIL, exit_code=1. Start/end/current fingerprint matched before this append:
  9bf243e57acf258072094a4289f78925149144b95575ff98621cf432fea0ca2c.
- Main/Android test compilation and test APK packaging succeeded: missing fail
  import is resolved; no new Kotlin compile errors were reported.
- Device execution reported Starting 0 tests / Finished 0 tests, followed by
  No test results. UTP XML has tests=0; textproto reports INSTRUMENTATION_FAILED.
- Persisted UTP logs show use_orchestrator=true, but installer fixture contains
  only app-debug.apk and app-debug-androidTest.apk, with no test helper installables.
  Device crash buffer reports ClassNotFoundException for
  androidx.test.services.shellexecutor.ShellMain in the orchestrator launch path.
- app/build.gradle.kts enables ANDROIDX_TEST_ORCHESTRATOR/clearPackageData but
  declares no androidTestUtil orchestrator dependency. File is unmodified in
  the working tree. This is a concrete test infrastructure wiring gap; not a
  PDF assertion failure or device acceptance.
- Official setup reference (primary source):
  https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner
  (Enable from Gradle requires androidTestUtil; CLI setup installs helper APKs).
- No active validation lock; emulator-5554 remains online. No tests skipped to
  force green, no orchestrator disabling, no runner/guard/baseline changes, no
  automatic retry. Request approval for the narrow app/build.gradle.kts dependency
  correction before editing this newly discovered out-of-Batch-7 file.
- Native PDF outcome: NOT EXECUTED; suite startup failed. The Android test code
  is now compiled, but its behavior remains unvalidated. Independent gates pending.

## User-approved orchestrator wiring repair

User approved adding the missing test dependencies in app/build.gradle.kts and
retrying the connected suite. One dependency line added:
androidTestUtil("androidx.test:orchestrator:1.6.1"). Official Google Maven POM
declares test-services:1.6.0 transitively, so no duplicate helper declaration or
unrelated Android test-library upgrade is necessary. The dependency is on the
test utility APK configuration, not production or test implementation classpaths.

Primary references verified:
- https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner
- https://developer.android.com/jetpack/androidx/releases/test (orchestrator 1.6.1)
- https://dl.google.com/dl/android/maven2/androidx/test/orchestrator/1.6.1/orchestrator-1.6.1.pom

Local source review: PASS; issues: None. Preserve ANDROIDX_TEST_ORCHESTRATOR and
clearPackageData=true unchanged; no weakening test isolation, skips, assertions,
runner or guard changes. Previous approved JUnit import remains. Native acceptance
still pending actual execution; no automatic PASS claim. Emulator-5554 is online,
no live validation lock or competing client at preflight. Serialized connected-tests
retry is pending; freeze this packet and candidate manifest during execution.

## Orchestrator-fixed connected terminal evidence - 2026-10-12

- Run ID: vr-20261012-070252-55901f2e; connected-tests, Pixel_8a Android 16,
  emulator-5554. User approved the utility dependency and full-suite retry.
- Command: gradlew.bat :app:connectedDebugAndroidTest --console=plain --no-parallel
  --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon,
  executed only through scripts/validation-runner.ps1.
- Terminal: FAIL / exit 1 / E_COMMAND_FAILED; elapsed 2422 seconds; finished
  2026-10-12T07:43:17.2820791Z. Result, stdout.log, stderr.log and physical
  complete.marker inspected under build/validation-runs/vr-20261012-070252-55901f2e/.
  Marker reads VALIDATION_COMPLETE, status=FAIL, exit_code=1.
- Start/end/current fingerprint matched before this documentation update:
  61c87784040c2a5b330530a66104d865f2630c6fe9ef4c96dbf7ca3f6828124f.
  No fingerprinted edits occurred during execution. Documentation changes make
  this evidence historical to that full worktree hash; source/test hashes retained.
- Actual orchestrator/helper APKs installed and instrumented tests executed.
  Main/test compilation and APK packaging succeeded; both narrow wiring repairs
  worked. The final failure is real test evidence, not the previous startup gap.
- Current XML: app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_8a(AVD) - 16-_app-.xml,
  timestamp 2026-10-12T07:43:08: 350 testcases, 43 failures, 0 errors, 9 skipped,
  298 passed. Console progress counted 359 executions; use the final XML's 350
  distinct reported testcases, not an inflated pass count from console progress.
- AccountingExportPdfRepositoryInstrumentedTest:
  - mixedCurrencyReportSerializesAndRendersMultiplePagesWithoutFx: PASS, 3.02s,
    no failure/error/skip. Actual PdfDocument serialization and PdfRenderer page
    rasterization ran; multiple pages and zero FX calls asserted.
  - exportExpensesAccountantReportPdfWritesPdfOutput: FAIL, 3.739s, instrumented
    process crash. Device evidence identifies SIGABRT in libmockkjvmtiagent.so
    while validating/retransforming a class. Exact mock target is unconfirmed;
    do not claim repository serialization/rendering reached or blame production
    PDF arithmetic from this native mocking failure.
  - Per-test logcat and test-result.textproto are in the same connected output's
    Pixel_8a(AVD) - 16 directory. Keep raw crash artifacts there; no stack traces,
    SQL messages, raw payloads or exception paths copied into this packet.
- Failure classes/counts:
  - DatabaseMigrationTest: 26.
  - DatabaseMigrationMatrixTest: 6.
  - Migration148to149ContractTest: 2.
  - MigrationContractTest: 4.
  - FreshInstallIndexParityTest: 3.
  - GroupMemberDaoTest: 1.
  - AccountingExportPdfRepositoryInstrumentedTest: 1.
- Read-only classification by tested version/contract: 29 failures exercise
  pre-v145 prototype paths; 9 exercise current-baseline migration/snapshot
  contracts; 4 exercise fresh-install index/uniqueness contracts; 1 native PDF
  fixture crash. This is triage, not proof all affected production paths are bad.
  Current-baseline evidence includes schema JSON decoding failures, test-builder
  migration-path gaps, and missing fixture tables in 148-to-149 contract tests.
  Fresh-install assertions/fixtures conflict with actual uniqueness behavior;
  verify intended contracts before editing either tests or constraints.
- Scoped git diff --check: PASS. Local earlier source reviews remain source-only;
  runtime gate FAIL and independent acceptance NOT RUN. No skip additions, data
  destruction, schema/migration edits or guard/runner/allowlist weakening.
- No active validation lock remains. Queue exited on the recorded test failure;
  no automatic retry or other batch started. Emulator left online. Mixed tree:
  153 tracked modified / 55 untracked paths, none staged or committed.

## First-release database baseline discussion (planning only)

- User reports no external app users and asked about a clean first-release
  database followed by future migrations. Recommended in principle: finalize
  the schema, verify fresh creation/backup/import, explicitly decide which
  prototype upgrade paths are supported, then freeze the release baseline.
- Release version 1.0 is independent of Room's database version. Current disk
  truth is APP_DATABASE_SCHEMA_VERSION=149, migration baseline=145, registry
  145-to-146-to-147-to-148-to-149. Do not reset the DB number merely to label
  the application release 1.0.
- Canonical AppDatabase builder registers ALL_MIGRATIONS and does not configure
  destructive fallback. Older SchemaPolicy/top-level comments suggesting such
  fallback are inconsistent with the actual builder. Below-v145 databases use
  a separately gated rescue/import design, not an implicit guaranteed upgrade.
- Baseline cleanup must be a separately scoped change with explicit preservation
  or reset consent for the user's own data. An old-schema raw database backup is
  not proof a new baseline can restore/import it. No backup round-trip or new
  baseline compatibility claim has been made here.
- Retiring unsupported prototype upgrade coverage intentionally is different
  from deleting failing supported-version/fresh-install tests to obtain green.
  The 9 current-baseline and 4 fresh-install failures still need reconciliation;
  the native PDF fixture crash is unrelated to migration history.
- User's acknowledgement recorded as agreement with the direction, not consent
  to erase data, remove migrations/tests, weaken guards or enable destructive
  fallback. No such edits implemented. Future public upgrades preserve user data.
