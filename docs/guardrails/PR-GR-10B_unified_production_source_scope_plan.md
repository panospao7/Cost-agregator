# PR-GR-10B — Unify production source-scope authority across guards

## Agent mission

Make one declared production-source-root contract authoritative for **every guard that scans production Kotlin**.

After this PR:

- all production Kotlin scanners consume the same source-root manifest;
- adding `src/main/kotlin` or a production module cannot create a silent guard blind spot;
- source-root topology is independently verified before source-scope claims;
- a malformed, missing, unreadable, undeclared, ambiguous, or symlink-escaping root fails closed with exit `2`;
- each registry guard explicitly states whether it scans production Kotlin, filtered production Kotlin, exact declared Kotlin targets, test sources, configuration, or artifacts;
- no executable guard silently hard-codes `app/src/main/java`.

## Why this PR is necessary

The DB guard already has a strict root manifest and root-verification machinery. At the reviewed SHA, the checked-in manifest declares only `:app` / `app/src/main/java`, and DB root resolution validates manifest shape, topology, Kotlin discovery, safe source resolution, and declared-path membership. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/config/guards/production_source_roots.yml))

But other active guards still independently hard-code that Java root. The time guard defines `SOURCE_SUBDIR = "app/src/main/java"` and validates exceptions against that prefix; UI DAO and worker guards construct the same source path directly; source provenance and receipt-link guards also use an independent source directory/scope constant. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_time_boundaries.py))

The existing root topology meta-guard is strong but deliberately limited: it reads `settings.gradle.kts`, observes conventional Java/Kotlin roots, and fails closed on unsupported layouts, but it explicitly does not inspect module-level build files for customized source sets or project directories. GR-10B must close that blind spot conservatively rather than guessing Gradle semantics. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/verify_production_source_roots.py))

The acceptance gate requires every guard to declare source scope and requires complete declared-scope scanning, nonempty plausible scan counts, required roots, and sentinel discovery. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

# Dependency and start gate

## Required predecessor

GR-10A must be merged first.

GR-10A provides:
- one canonical registry-derived command plan;
- a registered runner;
- execution-plan artifacts;
- a place to declare each guard’s required source-scope input;
- removal/classification of unregistered scanner authority.

## Preconditions

1. GATE-00R evidence exists for exact start SHA.
2. GR-10A registry plan is active and passes.
3. Checkout is clean.
4. Current source-root topology check result is captured.
5. Every active guard is classified in the source-scope matrix before production guard code is changed.
6. No other agent owns Gradle.
7. Source-root manifest, DB policy, DB baseline, and time exceptions hashes are recorded.

## Hard stops

Stop if:

- current topology verifier exits `2` for an unreviewed reason;
- current source layout contains a root not represented in the manifest;
- a guard’s intended scan scope cannot be classified;
- migration would require changing application architecture rather than scanner scope;
- moving a guard to all roots creates findings and someone proposes a baseline update;
- an existing guard relies on basename/path-suffix target lookup;
- a custom Gradle source-set layout cannot be safely modeled;
- any scope resolver would fall back silently to `app/src/main/java`.

---

# Scope

## Allowed

- neutral production-source-scope library;
- DB source-root compatibility facade/refactor;
- source-root topology verifier;
- source-scanning Python guards and their tests;
- registry source-scope metadata;
- source-scope documentation/matrix;
- narrowly necessary Gradle input declarations through GR-10A’s registered-runner bridge;
- source-root manifest only if exact topology evidence proves a real new root exists.

## Forbidden

- production Kotlin changes;
- DB ownership/structural policy changes;
- DB or other ratchet baseline changes;
- time exception expansion;
- broad path/package exclusions;
- manual source-root lists in individual guards;
- adding `app/src/main/kotlin` merely because it is conventional;
- accepting a custom Gradle source layout without explicit parser support;
- changing a real finding into a scope exemption.

---

# Core source-scope contract

## Authoritative source root

The only production-Kotlin root authority is:

```text
config/guards/production_source_roots.yml
```

The manifest remains declarative:

```text
schemaVersion
roots:
  module
  sourceSet
  path
```

The existing schema already supports conventional `src/main/java` and `src/main/kotlin` roots, strict ordering, duplicate/overlap rejection, and root topology checks. Preserve that strictness. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/source_roots.py))

## Scope classifications

Every registry entry must declare exactly one:

| Scope type | Meaning |
|---|---|
| `production-kotlin-all` | Scan every Kotlin file under every declared production root. |
| `production-kotlin-filtered` | Enumerate every declared production file first, then apply a guard-specific semantic filter such as Worker/UI relevance. |
| `production-kotlin-targeted` | Resolve one or more exact repository-relative source targets under declared roots; zero or multiple matches fail closed. |
| `test-source` | Intentionally scans test trees; does not use production root manifest. |
| `repository-config` | Scans YAML, Gradle, schema, allowlists, docs, or repository metadata only. |
| `artifact` | Inspects built APK/AAB/output only. |
| `external-tool` | Registered external tool has separately declared input scope and artifact contract. |

No active guard may have an implicit/unknown scope.

## Important distinction

A semantic filter is not a root filter.

Example:

```text
UI DAO guard:
  enumerate all production Kotlin roots
  then determine which files are UI/ViewModel relevant
```

It must not begin with:

```text
scan app/src/main/java only
```

---

# Required architecture

## 1. Neutral shared source-scope module

Create a neutral module, for example:

```text
scripts/guardrails/production_source_scope.py
scripts/guardrails/test_production_source_scope.py
```

Move or reuse the existing proven root-manifest behavior from `scripts/db_guard/source_roots.py`.

The neutral module must own:

| API | Required behavior |
|---|---|
| `load_production_source_manifest()` | Load strict manifest; no silent default in real repository execution. |
| `verify_production_source_topology()` | Compare manifest with supported Gradle topology; fail closed. |
| `resolve_production_source_scope()` | Return validated root set or controlled diagnostics. |
| `iter_production_kotlin_files()` | Deterministic root-order then canonical path-order traversal. |
| `resolve_production_kotlin_file()` | Exact safe repository-relative source-file resolution. |
| `is_declared_production_path()` | Segment-safe membership check. |
| `scope_evidence()` | Roots, source-file count, ordered file-list hash, manifest hash. |

No library function calls `sys.exit`.

## 2. DB compatibility layer

Do not break DB policy/scanner APIs or report contracts merely to rename a module.

Use one of these safe approaches:

1. move pure implementation to neutral module and make `scripts/db_guard/source_roots.py` a thin compatibility re-export; or
2. keep current implementation temporarily but add a neutral facade that delegates to it without duplicating logic.

Required invariant:

```text
There is one live implementation of root parsing, topology validation,
file enumeration, membership, and safe path resolution.
```

The current DB module still contains a legacy hard-coded `APPROVED_PRODUCTION_SOURCE_ROOTS` tuple and implicit fixture fallback; no production guard may rely on either after GR-10B. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/source_roots.py))

## 3. No production fallback

For a repository-level guard invocation:

```text
manifest missing → exit 2
manifest malformed → exit 2
manifest root undeclared → exit 2
manifest/topology mismatch → exit 2
```

Implicit conventional-root fallback may remain only in isolated unit-test helpers with an explicitly supplied synthetic `SourceRootSet`; it must never be reached by a normal repository CLI, suite, ratchet, or Gradle task.

## 4. Safe source file model

Use a value object equivalent to:

```text
ProductionSourceFile:
  repositoryRelativePath
  absolutePath
  rootPath
  module
  sourceSet
```

Rules:
- paths are repository-relative POSIX in reports;
- files are regular readable `.kt` files;
- source-file symlinks escaping repository fail closed;
- roots are walked deterministically;
- overlapping roots are impossible;
- no guard independently calls `rglob("*.kt")` or `os.walk(app/src/main/java)` for production scope.

---

# Required topology contract upgrade

The current topology verifier recognizes literal `include(...)` calls and conventional roots but intentionally does not inspect module build files. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/ci/verify_production_source_roots.py))

GR-10B must extend it conservatively.

## Supported layout

Only support:
- literal module includes;
- standard module-directory mapping;
- conventional:
  - `<module>/src/main/java`;
  - `<module>/src/main/kotlin`;
- no dynamic module/project directory/source-set mutation.

## Required unsupported-layout detection

Inspect:
- `settings.gradle.kts`;
- root `build.gradle.kts`, if present;
- every declared module’s `build.gradle.kts`, if present.

Reject with controlled source-layout diagnostic when a supported parser sees source-layout customization it cannot model, including relevant uses of:

```text
sourceSets
android.sourceSets
kotlin.sourceSets
srcDir
srcDirs
java.srcDirs
kotlin.srcDirs
projectDir
rootProject.projectDir
setRoot
```

Do not attempt to evaluate arbitrary Gradle/Kotlin DSL.

If comments/strings make reliable detection impossible, fail closed instead of guessing.

## Topology behavior

| State | Result |
|---|---|
| New Kotlin-containing `src/main/kotlin` root exists but is absent from manifest | exit `2` before scanners run |
| Manifest declares root not observed in supported topology | exit `2` |
| Root unreadable/symlink escape | exit `2` |
| Dynamic include/source-set/projectDir layout | exit `2` |
| Standard multi-module Java/Kotlin roots declared exactly | exit `0` |
| Test/debug/release/generated roots | never treated as production roots |

---

# Required source-scope matrix

Create:

```text
docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.yml
```

It is mandatory before implementation.

For every registry entry and every external enforcement engine identified by GR-10A, record:

```yaml
guardId: db_access
engine: python-ratchet
scopeType: production-kotlin-all
currentScopeMechanism: shared-db-root-manifest
targetScopeMechanism: production-source-scope
manifestRequired: true
rootTopologyRequired: true
semanticFilter: none
requiredTargets: []
currentHardcodedLiterals: []
migrationStatus: PENDING | MIGRATED | NOT_APPLICABLE
tests:
  positiveSecondRoot: ...
  undeclaredRootFailure: ...
  malformedManifestFailure: ...
owner: "@panospao7"
```

### Minimum classifications to inspect

Do not assume the list is complete; audit every registered script.

| Likely class | Examples requiring review |
|---|---|
| All production Kotlin | DB, time, receipt-link, many privacy/money/event/cancellation scanners |
| Filtered production Kotlin | UI DAO, workers, import lifecycle, cloud payload |
| Targeted production Kotlin | source provenance’s named coordinator/provenance targets |
| Config/test-only | allowlist compliance, migration matrix, lint baseline, ignored-test budget |
| Build/artifact | DI/release and release-artifact verification |
| External | PowerShell currency guard if retained after GR-10A |

No matrix entry may say `same as before` without identifying actual current code path.

---

# Mandatory migration sequence

## Step 1 — Freeze exact topology

Before edits:

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

python3 scripts/ci/verify_production_source_roots.py \
  --root . \
  --manifest config/guards/production_source_roots.yml
```

Record:

```bash
find . -type d \( -path '*/src/main/java' -o -path '*/src/main/kotlin' \) -print
```

Also inspect:
- `settings.gradle.kts`;
- root/module `build.gradle.kts`;
- current manifest;
- source-root references across executable scripts;
- GR-10A execution-plan required inputs.

Do not change the manifest until actual current topology is proven.

## Step 2 — Add neutral scope tests before migration

Required tests:

1. valid current single-root manifest;
2. valid Java + Kotlin roots;
3. valid two-module roots;
4. canonical root/file ordering;
5. unknown/malformed manifest keys;
6. empty roots;
7. duplicate/overlap roots;
8. absolute, traversal, backslash, wildcard paths;
9. test/debug/release/generated roots;
10. missing/unreadable root;
11. root symlink escape;
12. source file symlink escape;
13. unreadable Kotlin file;
14. manifest absent in repository CLI mode;
15. explicit synthetic root set works only in test/library mode;
16. undeclared Kotlin-containing root fails;
17. module-level custom source-set marker fails;
18. dynamic include/projectDir layout fails;
19. identical tree produces identical file list/hash;
20. all output paths are repository-relative and safe.

## Step 3 — Migrate DB to neutral authority without semantic change

First migrate DB consumers:
- inventory;
- declaration scanner;
- v2 policy evidence;
- D4 scanner;
- DB CLI;
- candidate tools;
- DB source-root topology CLI.

Requirements:
- current DB report semantic content remains identical under the current one-root manifest;
- policy path validation still rejects test/generated paths;
- root-manifest errors remain DB controlled diagnostics and exit `2`;
- no DB baseline changes;
- two DB scans have equal semantic output.

Only after DB parity passes should non-DB guards consume the neutral API.

## Step 4 — Add source-root meta-guard to canonical registry

Register `production_source_roots` as a blocking/policy guard.

Requirements:
- executes early in canonical suite order;
- exits `0` only on exact manifest/topology agreement;
- exits `2` on any root uncertainty;
- produces a safe scope-evidence artifact;
- every production-Kotlin guard runs after it in order but independently validates scope too;
- suite still runs all guards even when this meta-guard fails, so each guard’s own fail-closed behavior is observable.

## Step 5 — Migrate time guard carefully

Replace:
- `SOURCE_SUBDIR`;
- `_CANONICAL_PATH_PREFIX`;
- direct `root / app/src/main/java`;
- direct `rglob("*.kt")`.

With neutral scope APIs.

Requirements:
1. exceptions may point to any declared production root;
2. exception paths remain exact repository-relative paths;
3. stale exception detection searches all declared roots;
4. a source root failure exits `2`;
5. `Calendar.Builder`/time detection semantics do not change;
6. no time exception changes in this PR;
7. current single-root findings remain semantically identical before/after.

The current time guard is explicitly Java-root hard-coded, so this migration is mandatory. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_time_boundaries.py))

## Step 6 — Migrate filtered source guards

For each `production-kotlin-filtered` guard:
1. enumerate all declared production files through the shared iterator;
2. retain its semantic relevance predicate;
3. remove private root constants and `SKIP_DIRS` used as root-scope substitutes;
4. preserve repository-relative violation paths;
5. make unreadable/scope failure exit `2`;
6. add a second-root positive fixture.

UI DAO and worker guards currently build `app/src/main/java` directly and walk it independently; their scope must become manifest-backed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_ui_dao_boundaries.py))

## Step 7 — Migrate targeted source guards

For guards with named expected files:
- use exact repository-relative target paths;
- resolve target through `resolve_production_kotlin_file`;
- require exactly one safely resolved target;
- reject target outside declared root;
- reject missing/unreadable/symlink target with exit `2`;
- never fall back to basename/suffix search.

Source provenance currently combines whole-tree scans with named paths under the app Java root, so it needs both all-root enumeration and exact target resolution. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_source_provenance_boundaries.py))

## Step 8 — Migrate remaining all-source guards

For each remaining source-scanning guard:
- replace direct `os.walk`, `Path.rglob`, and hard-coded root constants;
- use shared ordered files;
- preserve rule semantics;
- update tests with a second root and undeclared-root failure;
- record scan count/root-list evidence safely.

Receipt-link currently has a separate `SCOPE_DIR = "app/src/main/java"` and must be migrated. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_receipt_link_boundaries.py))

## Step 9 — Handle external/non-Python source scanners

If GR-10A retained an external scanner such as `currency_guardrails.ps1`:

1. it must consume a canonical file list or manifest-backed scope input;
2. it must not receive a hard-coded `-SourceDir app/src/main/java`;
3. it must fail if scope evidence is missing/malformed;
4. it must be tested with a second production root.

If that cannot be achieved safely, extract or retire it under GR-10A’s decision ledger. Do not exempt it from source-scope authority.

## Step 10 — Literal and ownership audit

Run:

```bash
git grep -nE \
  'app/src/main/java|app/src/main/kotlin|SOURCE_DIR|SOURCE_SUBDIR|SCOPE_DIR|rglob\(.+\.kt|os\.walk' \
  -- scripts app .github \
  | tee build/guard-debug/gr10b/source-scope-literal-audit.txt
```

Classify every result:

| Allowed result | Not allowed |
|---|---|
| root manifest data | executable private production-root selection |
| topology-observer conventional-root probe | guard-specific source walk |
| test fixture | production suite hard-coded scope |
| documentation explaining current manifest | hidden CI source-dir argument |

No executable production scanner may retain a private root list.

---

# Required adversarial tests

## Common source-scope adversarial matrix

1. Add a relevant `.kt` file only under declared `src/main/kotlin` → relevant guard sees it.
2. Add a relevant `.kt` file only under a declared second module → relevant guard sees it.
3. Add Kotlin root without manifest entry → every scoped guard exits `2`; none silently scans partial scope.
4. Add manifest root with no supported topology → exit `2`.
5. Add malformed source-set DSL → topology guard exits `2`.
6. Add root symlink outside repository → exit `2`.
7. Add Kotlin file symlink outside repository → exit `2`.
8. Add unreadable root/file → exit `2`.
9. Add test/androidTest/debug/generated Kotlin files → not treated as production roots.
10. Run twice → root order, file list hash, scan counts, findings, and diagnostics are equal.
11. Add direct time API in second root → time guard finds it.
12. Add direct DAO mutation in second root → DB guard finds it.
13. Add Worker/UI/receipt fixture in second root → corresponding semantic guard finds it.
14. Add target-path ambiguity → targeted guard exits `2`.
15. Delete a declared root after manifest remains → exit `2`.

## Real repository proof

1. topology meta-guard passes;
2. all declared roots contain expected Kotlin content;
3. every source-scanning guard reports only declared production scope;
4. DB inventory paths are all declared;
5. time scan covers all declared files;
6. current manifest remains unchanged unless a real root was found;
7. static suite completes with no scope infrastructure failures;
8. Gradle DB/time wrappers consume the same manifest-backed plan.

---

# Required validation

## Python

```bash
python3 -m pytest \
  scripts/guardrails/test_production_source_scope.py \
  scripts/test_db_guard_source_roots.py \
  scripts/test_db_guard_source_roots_integration.py \
  scripts/ci/test_verify_production_source_roots.py \
  scripts/test_verify_time_boundaries.py \
  scripts/test_verify_ui_dao_boundaries.py \
  scripts/test_verify_worker_boundaries.py \
  scripts/test_verify_receipt_link_boundaries.py \
  scripts/test_verify_import_lifecycle_boundaries.py \
  scripts/test_verify_cloud_payload_boundaries.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_scanner_d4.py \
  -v --tb=short
```

Use actual existing test paths where filenames differ. Add every migrated guard’s test file.

```bash
python3 scripts/ci/verify_production_source_roots.py \
  --root . \
  --manifest config/guards/production_source_roots.yml
```

```bash
python3 scripts/ci/verify_guard_registry.py --root .
```

```bash
python3 scripts/verify_time_boundaries.py \
  --root . \
  --allowlist config/guards/time_boundary_exceptions.yml \
  --fail-on-violation
```

```bash
python3 scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  --findings-output build/guard-debug/gr10b/db-findings.json
```

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --mode ci \
  --output-dir build/guard-debug/gr10b/static-suite
```

## Gradle — sequentially only

```bash
./gradlew :app:checkDirectTimeCalls \
  --no-daemon --stacktrace --console=plain
```

```bash
./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --console=plain
```

```bash
./gradlew :app:check \
  --no-daemon --stacktrace --console=plain
```

Then run GATE-00R twice for the final SHA.

---

# Preservation checks

```bash
git diff --exit-code -- \
  app/src/main \
  config/baselines \
  config/guards/db_ownership_policy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/time_boundary_exceptions.yml
```

Expected:
- no production Kotlin change;
- no DB policy/baseline change;
- no time exception change.

`production_source_roots.yml` may change only if the start-SHA topology audit proves an actual Kotlin-containing conventional production root was omitted. Document exact evidence.

---

# Definition of done

GR-10B is complete only when:

- every source-scanning guard has an explicit registry scope classification;
- one neutral source-scope implementation owns root loading, validation, traversal, and safe resolution;
- DB compatibility behavior is preserved;
- no normal repository guard uses implicit `app/src/main/java` fallback;
- no executable production scanner privately hard-codes a production root;
- time, DB, UI, worker, receipt-link, provenance, and all other applicable guards scan all declared roots;
- scoped guards fail with exit `2` on any root uncertainty;
- topology detects unsupported module-level source layout rather than guessing;
- undeclared second roots are caught before partial scanning;
- tests prove second-root detection and root-failure behavior;
- no baseline/policy/exception weakening occurred;
- direct, suite, ratchet, and Gradle results reproduce twice through GATE-00R.

## Required completion report

```text
PR: GR-10B
START SHA:
END SHA:
GATE-00R INPUT SHA:
GR-10A DEPENDENCY SHA:

DECLARED ROOTS BEFORE:
DECLARED ROOTS AFTER:
OBSERVED ROOTS:
MANIFEST CHANGED: yes/no
MANIFEST CHANGE JUSTIFICATION:

ACTIVE GUARDS:
SOURCE-SCOPE MATRIX ENTRIES:
PRODUCTION-KOTLIN-ALL GUARDS:
FILTERED-PRODUCTION-KOTLIN GUARDS:
TARGETED-PRODUCTION-KOTLIN GUARDS:
NON-PRODUCTION-SCOPE GUARDS:

PRIVATE ROOT LITERALS BEFORE:
PRIVATE ROOT LITERALS AFTER:
IMPLICIT PRODUCTION FALLBACKS IN NORMAL CLIS: 0

SECOND-ROOT POSITIVE TESTS:
UNDECLARED-ROOT FAILURE TESTS:
SYMLINK/UNREADABLE FAILURE TESTS:
CUSTOM-SOURCESET FAILURE TESTS:
REAL TOPOLOGY RESULT:

TIME RESULT:
DB RESULT:
STATIC SUITE RESULT:
GRADLE TIME RESULT:
GRADLE DB RESULT:
GRADLE CHECK RESULT:
GATE-00R DOUBLE CAPTURE REPRODUCIBLE: yes/no

PRODUCTION KOTLIN CHANGED: no
DB POLICY CHANGED: no
DB BASELINE CHANGED: no
TIME EXCEPTIONS CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT PR:
```