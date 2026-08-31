# GR-10A Command-Authority Matrix

PR: **PR-GR-10A — Canonicalize registry → suite → ratchet → Gradle command ownership** (Slice 3)
Plan: `docs/guardrails/PR-GR-10A_canonical_command_ownership_plan.md` (deliverable 5 + mandatory decision gate + Steps 6–8)

Direction of authority after this PR:

```text
guard_registry (execution schema)
   ↓
guard_execution_plan (compile)
   ├── run_static_guard_suite (suite legs)
   ├── run_registered_guard (direct / Gradle bridge)
   ├── Gradle wrapper tasks (thin wrappers only)
   └── verify_guard_registry (plan validation)
        ↓
guard_ratchet (tokenized child argv)
```

Declared-external engines are registry-declared but excluded from the
canonical suite plan (plan Step 5: "unless declared external") and are never
executed by the Python runner bridge.

## 1. Command-authority matrix (8 rows — no row UNKNOWN)

| # | Enforcement path | Current owner (pre-PR) | Canonical post-PR owner | Resolution | Evidence |
|---|---|---|---|---|---|
| 1 | Static suite direct guards | suite `GUARD_MANIFEST` (migrated to registry-derived plan in Slice 2) | registry execution spec → `compile_static_suite_plan` | **migrated (Slice 2)** | `run_static_guard_suite.py` derives every default leg from the registry; no production command list remains; `execution-plan.json` evidence written per run |
| 2 | Static suite ratchet guards | suite `GUARD_MANIFEST` (migrated in Slice 2) | registry execution spec → `compile_static_suite_plan` → tokenized `--command-arg` child argv | **migrated (Slice 2)** | `db_access` compiles to protocol 2, `db_access_v2.json` baseline, D4 profile; `test_run_static_guard_suite.py::TestDerivedPlanEqualsLegacyManifest` proves semantic equality with the recorded pre-migration manifest |
| 3 | DB Gradle task (`verifyDbAccessBoundaries`) | KTS command construction (ratchet argv, `--command-arg` tokens, baseline/policy paths, `--ci-mode`) | registered runner: `run_registered_guard.py --guard-id db_access --context gradle --root <repo> --ci-mode` | **migrated (Slice 3)** | `app/build.gradle.kts` task is a thin wrapper (interpreter + root + exit mapping only); the compiled plan adds explicit `--finding-protocol=2` (rule 4) and resolves the identical policy/structural inputs; contract pins: `scripts/ci/test_gradle_db_guard_contract.py` |
| 4 | Time Gradle task (`checkDirectTimeCalls`) | KTS command construction (`--root`, `--allowlist`, `--fail-on-violation` argv) | registered runner: `run_registered_guard.py --guard-id time_boundaries --context gradle --root <repo> --ci-mode` | **migrated (Slice 3)** | registry `time_boundaries.execution.arguments` owns the exact argv; Gradle owns interpreter/root/exit mapping; contract pins: `scripts/ci/test_gradle_registered_guard_contract.py` |
| 5 | Inline lifecycle scanner (`checkLifecycleBypasses` + `checkLifecycleBypass`, Gradle KTS) | Gradle KTS inline scanners rooted at `app/src/main/java` | canonical `db_access` D4 guard (scanner + ownership policy + v2 ratchet baseline) | **SUBSUMED_AND_RETIRED** | decision ledger §2.1; proof tests: `scripts/test_lifecycle_scanner_subsumption.py` |
| 6 | Inline money scanner (`checkRawMoneyAggregates`, Gradle KTS) | Gradle KTS inline scanner rooted at `app/src/main/java` | registered guard `raw_money_aggregates` (`scripts/verify_raw_money_aggregates.py`, G-MONEY-RAW-01..07) + Gradle bridge task | **EXTRACTED_AND_REGISTERED** | decision ledger §2.2; extraction tests: `scripts/test_verify_raw_money_aggregates.py` |
| 7 | PowerShell currency guard in CI (`scripts/currency_guardrails.ps1`, `unit-tests` job) | workflow step (manual `-SourceDir app/src/main/java` invocation) | registry-declared external execution entry (`currency_guardrails_ps`, engine `external`) | **REGISTERED_EXTERNAL_ENGINE** | decision ledger §2.3 |
| 8 | Release artifact verifier (`scripts/verify_release_artifact.py`, `release-check` job) | workflow job step | registry-declared external execution entry (`release_artifact`, engine `external`) | **REGISTERED_EXTERNAL_ENGINE (classified explicitly)** | decision ledger §2.4 |

## 2. Mandatory decision gate: legacy/unregistered enforcement

Adjudicated with evidence (rule inventories, fixture proofs, fresh scan
evidence) — **not** rule-name similarity.

### 2.1 Inline lifecycle scanners — `SUBSUMED_AND_RETIRED`

Retired tasks: `checkLifecycleBypasses` (14 textual `expenseDao.updateXxx(`
patterns + 7-file allowlist) and `checkLifecycleBypass` (textual
`expenseDao\.insert|update|delete` regexes + 10-class-name allowlist).

**Rule enumeration → canonical mapping.** Every textual rule of both retired
scanners names an `ExpenseDao` interface mutation method (verified in
`app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`:
`insert` L95, `update` L114, `delete` L299, `updateCategory` L303,
`updateCategoryNullable` L307, `updateMerchantAndKey` L345,
`updateTransactionType` L349, `updateTransferDirection` L357,
`updateTransferAccountName` L361, `updateIsNotMine` L365, `updateOwnerName`
L369, `updateIsSharedExpense` L373, `updateSharedWithName` L377,
`updateMySharePercentage` L381, `updateMyShareAmount` L385,
`updateLocation` L1889, `clearLocation` L1941). The canonical `db_access` D4
guard discovers direct DAO mutations by **receiver TYPE** through the Room
mutator inventory (`scripts/db_guard/room_inventory.py`) and authorizes each
discovered mutation by **exact full-identity equality** against the ownership
policy (`scripts/db_guard/scanner.py`, `PolicyEntry`/`match_mutation`: path +
ownerFqcn + kind + method + receiver + ordered parameterTypes + daoAccessor +
daoFqcn + operation). An unauthorized mutation is a `DB_UNAUTHORIZED_MUTATION`
finding (ratchet-enforced against `config/baselines/db_access_v2.json`); an
unresolvable receiver/DAO scope is a **blocking** diagnostic (exit 2 — the
scan is untrusted, CI fails). There are no wildcards, no name-only matching,
and no v1 fallback.

**Fixture proof** (`scripts/test_lifecycle_scanner_subsumption.py`):

1. *Rule mapping* — the Room inventory discovers a mutator identity for every
   method in the retired rule surface (17/17).
2. *Positive fixtures* — a fixture tree with one unauthorized call site per
   retired rule produces one `DB_UNAUTHORIZED_MUTATION` finding per site,
   covering the exact retired surface (the retired file-name allowlists have
   no canonical counterpart: the ownership policy is the single
   authorization authority).
3. *False-positive class* — the retired scanners' documented false positive
   (KDoc `[ExpenseDao.insertAtomic]` text matches; recorded in
   `VALIDATION_FINDINGS_2026-08-09.md` Update 7) produces **no** D4 finding:
   D4 masks comments before discovery. Retirement loses no real enforcement.
4. *Strict superiority* — D4 is receiver-TYPE based: a mutation call through
   a receiver **not** named `expenseDao` is invisible to the retired
   name-based regexes but discovered by D4. An unresolved receiver scope
   fails closed (blocking diagnostic) instead of passing silently.

**Fresh full-tree evidence** (`build/guard-debug/gr10c/gate-after.json`,
protocol-v2 scan of the live tree): `trusted: true`, `findingCount: 0`,
`files_scanned: 1017`, `inventory_daos: 68`, `inventory_mutators: 406`,
`policyMode: authoritative-v2`, 20 advisory (`DB_SIGNATURE_UNRESOLVED`,
`advisory: true`) diagnostics on non-DB callables. Every direct DAO mutation
in the current tree is policy-authorized; the retired scanners' extra flags
(e.g. the `ExpenseWriteStore.kt` matches recorded in
`VALIDATION_FINDINGS_2026-08-09.md` Update 7) are sites the canonical
ownership policy deliberately authorizes — stale-allowlist false positives
relative to canonical policy, not enforcement gaps.

**Policy/baseline semantics.** The retired scanners' allowlists were
filename/class-name heuristics; the canonical policy is exact-identity,
human-reviewed, and ratchet-enforced (new unauthorized writers fail; baselined
history is tracked in `config/baselines/db_access_v2.json`). D4 is at least
as strict on every real call site and strictly broader in discovery.

### 2.2 Inline money scanner — `EXTRACTED_AND_REGISTERED`

Retired task: `checkRawMoneyAggregates` (7 regex rules + 7-file allowlist +
`fromBuckets` block skip). Extracted 1:1 into the registered guard
`raw_money_aggregates` (`scripts/verify_raw_money_aggregates.py`) with stable
rule IDs:

| Stable rule ID | Retired KTS regex (verbatim) |
|---|---|
| G-MONEY-RAW-01 | `\.sumOf\s*\{\s*it\.amount\s*\}` |
| G-MONEY-RAW-02 | `\.sumOf\s*\{\s*it\.effectiveAmount\s*\}` |
| G-MONEY-RAW-03 | `\.sumOf\s*\{\s*it\.normalizedAmount\s*\}` |
| G-MONEY-RAW-04 | `\.sumOf\s*\{\s*it\.\w*[Pp]rice\s*\}` |
| G-MONEY-RAW-05 | `\.sumBy\s*\{\s*it\.amount\s*\.(?:toInt|roundToInt)\s*\(\)\s*\}` |
| G-MONEY-RAW-06 | `total\s*:\s*Double` |
| G-MONEY-RAW-07 | `var\s+total\s*=\s*0\.0\s*;?\s*//?\s*.*sum` |

**Why not SUBSUMED_AND_RETIRED.** The canonical money guard
(`scripts/verify_money_boundaries.py`, G-MONEY-10–21) has **no** rule family
for raw Double aggregates in general production code: its rules cover raw
`ExpenseSnapshot` synthesis (G-MONEY-10, financial dirs only), currency
sentinels (G-MONEY-11), the misleading helper (G-MONEY-12), BudgetForecast
unavailable branches (G-MONEY-13), `normalizedInput` defaults (G-MONEY-14),
dashboard widget raw values (G-MONEY-15, scoped to
`ComputeDashboardWidgetsUseCase`), SpendingTrend conversion (G-MONEY-16),
`convertMultiple`/`StaleRatePolicy` (G-MONEY-17/18/19), and emptyList
fallbacks (G-MONEY-21). None detects `.sumOf { it.amount }`,
`.sumOf { it.effectiveAmount }`, or `total: Double` outside those narrow
scopes. The retired scanner's rule family is therefore NOT subsumed —
retiring it without extraction would delete real enforcement.

**Extraction fidelity.** The registered guard transcribes the seven patterns
byte-for-byte (pinned by
`test_rule_patterns_are_transcribed_from_the_kts_scanner`), the same 7-file
allowlist, the same test-tree skip, the same import/comment line skips, and
the same `fromBuckets` brace-depth state machine (including the triggering
line). One documented scoping correction: the test-tree skip is applied to
the scan-root-relative path instead of the absolute walked path, so the rule
cannot depend on the checkout location (the KTS scanner's absolute-path
`contains("test")` check was checkout-location-dependent). Exit mapping:
0 pass / 1 violation / 2 infrastructure.

**Suite participation + Gradle bridge.** Registry entry `raw_money_aggregates`
(engine `python-direct`, mode `blocking`, `--fail-on-violation`, standard
timeout profile, test manifest `scripts/test_verify_raw_money_aggregates.py`)
joined `SUITE_GUARD_ORDER` after `money`; the Gradle task
`checkRawMoneyAggregates` is now a thin wrapper invoking
`run_registered_guard.py --guard-id raw_money_aggregates --context gradle`.

**Current-tree outcome (inherited, not new).** The retired KTS scanner's own
rules, applied to the current tree, match sites outside its allowlist (e.g.
`OnDeviceReceiptItemCategorizationService.kt:170` `items.sumOf { it.amount }`,
`FinancialHealthCalculator.kt:137` `.sumOf { it.effectiveAmount }`,
`DailyBucketEngine.kt:22` `val total: Double,`) — the KTS task was
red-by-static-analysis before this PR (its exact Gradle outcome was
unobserved because `:app:check` short-circuits on earlier documented holds;
`VALIDATION_FINDINGS_2026-08-09.md` Update 7). The extraction changes no
rule and no source file, so the registered guard inherits exactly the same
findings — pre-existing tree debt made **visible** in the suite summary
instead of hidden behind Gradle short-circuiting. Resolution of those
findings (source cleanup or a money-rules baseline decision) belongs to the
money-rules owners and GR-10B (source-scope semantics); this control-plane
PR neither adds nor removes any finding.

### 2.3 PowerShell currency guard — `REGISTERED_EXTERNAL_ENGINE`

`scripts/currency_guardrails.ps1` is invoked by the CI `unit-tests` job
(`pwsh scripts/currency_guardrails.ps1 -SourceDir app/src/main/java
-ProjectRoot <workspace>`). Inventory of its rule families:

| Check | Rule | Blocking? |
|---|---|---|
| 1 | raw `.sumOf { ... .effectiveAmount }` without a preceding `// SAFE:` marker | **yes** (sets exit 1) |
| 2 | deprecated single-arg `CurrencyFormatter.format(amount)` calls | no (report only) |
| 3 | `"EUR"` hardcodes (excluding `CurrencyCode.EUR` / `CurrencyCode("EUR")` / `// LEGITIMATE:`) | no (report only) |

**Disposition: REGISTERED_EXTERNAL_ENGINE.** The proof surface is genuinely
non-Python (PowerShell), matching the plan's disposition definition
("registry records owner, command, scope, artifacts, and CI job"). The new
registry entry `currency_guardrails_ps` (engine `external`) records the
entrypoint, the `-SourceDir app/src/main/java` scope arguments, the output
contract (stdout report; exit 1 only for check 1), and anchors the evidence
to this matrix. Declared-external entries are excluded from the canonical
suite plan (warning diagnostic `E_ENGINE_EXTERNAL_SKIPPED`, never an error)
and are rejected by single-guard compilation (`E_ENGINE_NOT_COMPILABLE`,
exit 2) — the Python runner bridge cannot execute them.

**Overlap note (auditable, not hidden).** The blocking check-1 family
overlaps the registered `raw_money_aggregates` guard (G-MONEY-RAW-02 covers
the `it.effectiveAmount` spelling; the PS check-1 pattern is broader — any
single-line receiver — and carries the `// SAFE:` previous-line exemption
that G-MONEY-RAW-02 deliberately does not). The two surfaces are recorded
here as distinct enforcement facts; converging them (widening G-MONEY-RAW-02
or porting the `// SAFE:` semantics) is a **rule change** and is out of scope
for this control-plane PR.

**Deferred workflow cleanup (plan Step 8).** The `unit-tests` job keeps
invoking the PS guard; the invocation is no longer *hidden* (it is
registry-declared and auditable). Removing/replacing the workflow's manual
guard-command documentation and re-pointing jobs at canonical entries is
Step 8 workflow cleanup and requires editing `.github/workflows/ci.yml`,
which is outside this slice's edit scope. Required-job semantics,
`continue-on-error`, and CI failure policy are untouched.

### 2.4 Release artifact verifier — `REGISTERED_EXTERNAL_ENGINE` (classified explicitly)

`scripts/verify_release_artifact.py --fail-on-violation` runs in the separate
`release-check` CI job after `assembleRelease`. Per plan Step 7 it need not
enter the static suite, but the exclusion must be explicit and auditable.
The new registry entry `release_artifact` (engine `external`, mode
`blocking`, `--fail-on-violation`, documentation anchor this matrix) makes
the outside-the-suite status a registry fact instead of a comment. It is
excluded from the canonical suite plan by the same declared-external
mechanism as §2.3.

## 3. Gradle migration shape (Step 6)

Preserved public task names: `checkDirectTimeCalls`, `verifyDbAccessBoundaries`
(+ `checkRawMoneyAggregates`, now the extraction's bridge).

Gradle owns ONLY:

- the configured Python executable (`pythonInterpreter()`,
  `-PpythonExecutable` property first, then `python3`/`python` PATH probes)
  and its `--version` preflight (infrastructure error on failure);
- the repository root;
- task name/description identity;
- fail-closed validation of the runner script itself (outside-root, missing,
  non-regular, unreadable → `GradleException`);
- test-only override forwarding (typed, root-contained, gated);
- exit-to-`GradleException` mapping (0 pass / 1 violation / 2 infra /
  anything else unexpected).

Gradle must NOT (and after this PR does not) own: the DB child command, the
ratchet argv, the time guard argv, baseline/policy/allowlist path lists,
timeout arithmetic, or source-root input semantics.

**Test-only overrides (plan Step 6).** The four policy/manifest inputs are
the only declared `requiredInputs` of `db_access`, so only they are
overridable, forwarded as typed `--input-override KEY=PATH` pairs (relative
paths resolved against `rootDir`). The retired properties
`dbGuardRatchetPath` / `dbGuardScriptPath` / `dbGuardBaselinePath` are gone:
the ratchet script, guard entrypoint, and baseline are compiler-owned ratchet
metadata, and an override must never change guard ID, mode, baseline mode, or
protocol. Overrides require the dedicated test-mode property
`-PdbGuardTestOverrides=true`; without it, any override property is a hard
`GradleException`, and the runner itself rejects overrides outright in
`--ci-mode` (`E_TEST_OVERRIDE_IN_CI`, exit 2).

**Timeout note.** Neither the old nor the new DB Gradle command passes a
ratchet `--timeout`; the ratchet's own 300s default remains the guard policy
for Gradle-plane runs (the suite derives its own child budget as suite
adapter policy). The migration preserves this behavior exactly; the runner
bridge imposes no process timeout of its own.

## 4. Verification status

| Check | Status |
|---|---|
| Extraction tests (`scripts/test_verify_raw_money_aggregates.py`) | written — NOT RUN in this session (command execution prohibited) |
| Subsumption proof tests (`scripts/test_lifecycle_scanner_subsumption.py`) | written — NOT RUN in this session |
| Gradle wrapper contract tests (`test_gradle_db_guard_contract.py`, `test_gradle_registered_guard_contract.py`) | updated/written — NOT RUN in this session |
| Suite/registry tests (`test_run_static_guard_suite.py`) | updated — NOT RUN in this session |
| `python3 scripts/ci/verify_guard_registry.py --root .` | NOT RUN in this session |
| `./gradlew :app:verifyDbAccessBoundaries` / `:app:checkDirectTimeCalls` / `:app:check` | NOT RUN in this session (Gradle ownership rules) |

Recommended validation (sequentially, single Gradle owner):

```bash
python3 -m pytest scripts/test_verify_raw_money_aggregates.py \
  scripts/test_lifecycle_scanner_subsumption.py \
  scripts/ci/test_gradle_db_guard_contract.py \
  scripts/ci/test_gradle_registered_guard_contract.py \
  scripts/ci/test_run_static_guard_suite.py \
  scripts/ci/test_guard_execution_plan.py -v --tb=short
python3 scripts/ci/verify_guard_registry.py --root .
./gradlew :app:checkDirectTimeCalls --no-daemon --stacktrace --console=plain
./gradlew :app:verifyDbAccessBoundaries --no-daemon --stacktrace --console=plain
./gradlew :app:check --no-daemon --stacktrace --console=plain
```

## 5. Preservation

- Production Kotlin: untouched.
- `config/baselines/**`, `config/guards/db_ownership_policy.yml`,
  `config/guards/db_structural_exceptions.yml`,
  `config/guards/db_structural_exceptions_expected_methods.yml`,
  `config/guards/production_source_roots.yml`,
  `config/guards/time_boundary_exceptions.yml`: untouched.
- Guard rules: unchanged by command canonicalization (the extraction
  transcribes the retired rules 1:1; no rule was widened, narrowed, added,
  or removed).
