# PR-GR-03 — Declare production source roots

## Agent mission

Make production Kotlin source coverage explicit, centralized, and fail-closed.

This PR introduces one authoritative production-source-root manifest and one shared resolver used by Room inventory, declaration scanning, policy evidence, candidate generation, and DB CLI path resolution.

At the reviewed SHA, Room inventory, declaration scanning, and the DB verifier each independently hard-code `app/src/main/java`; that creates a future blind spot for `app/src/main/kotlin` or additional production modules. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/9b97e7979130de605d164386bbf719cf20579475/scripts/db_guard/room_inventory.py))

---

## Preconditions

Do not start unless:

- GR-00 inventory-only evidence is trusted;
- GR-01 shared `source_roots.py` interface exists;
- GR-02 candidate generation is merged and its checked-in candidate reproduces;
- checkout is clean;
- no agent is concurrently running Gradle.

Do not assume the root is still only Java. Determine the actual source topology from the start SHA before writing the manifest.

---

## Non-goals

Do **not**:

- activate v2 ownership policy;
- update the DB baseline;
- change Room schema, DAO behavior, or production Kotlin;
- add a feature module merely to test multi-root support;
- scan test, androidTest, debug, release, generated, build, or fixture trees;
- silently support arbitrary Gradle source-set DSL;
- refactor the registry/static-suite duplication from GR-10.

---

## New authoritative manifest

Create:

```text
config/guards/production_source_roots.yml
```

Required current shape, if the repository still has only that real root:

```yaml
schemaVersion: 1
roots:
  - module: ":app"
    sourceSet: main
    path: app/src/main/java
```

Do not add `app/src/main/kotlin` merely because it is conventional. Add it only when it is an actual production Kotlin root.

### Closed manifest contract

Top-level keys: exactly `schemaVersion`, `roots`.

Each root: exactly `module`, `sourceSet`, `path`.

Rules:

- `schemaVersion` is integer `1`;
- `roots` is non-empty;
- module is a declared module path such as `:app`;
- source set is exactly `main`;
- path is repository-relative POSIX;
- path is an exact conventional production source root ending in `/src/main/java` or `/src/main/kotlin`;
- root directory exists, is readable, and is not a symlink escape;
- duplicate, overlapping, test, debug, release, generated, absolute, traversal, or wildcard roots fail;
- unknown YAML keys fail;
- order is canonical: module then path.

---

## Required architecture

### Shared root module

Make `scripts/db_guard/source_roots.py` authoritative.

It must provide pure/library APIs equivalent to:

```text
load_source_root_manifest(...)
validate_source_root_manifest(...)
verify_declared_root_topology(...)
resolve_project_root(...)
iter_production_kotlin_files(...)
is_declared_production_path(...)
resolve_canonical_source_file(...)
```

No library module may call `sys.exit`. CLI adapters map controlled failures to exit `2`.

### Topology meta-guard

Implement a pure topology verifier plus a thin CLI adapter, for example:

```text
scripts/ci/verify_production_source_roots.py
scripts/ci/test_verify_production_source_roots.py
```

The verifier must:

1. identify modules declared through supported literal `settings.gradle.kts` includes;
2. inspect conventional production roots:
   - `<module>/src/main/java`
   - `<module>/src/main/kotlin`;
3. require every observed Kotlin-containing root to appear in the manifest;
4. reject a manifest root absent from supported project topology;
5. reject unsupported/dynamic project-layout or production-source-set configuration rather than guessing;
6. ignore test/androidTest/debug/release/build/generated directories;
7. return deterministic controlled diagnostics only.

If non-conventional production source roots are introduced later, the meta-guard must fail `DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED` until explicit support is implemented. A silent partial scan is forbidden.

### Controlled diagnostics

Register any new codes in the immutable diagnostic catalog and protocol tests. Use controlled codes equivalent to:

```text
DB_SOURCE_ROOT_MANIFEST_INVALID
DB_SOURCE_ROOT_UNDECLARED
DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED
DB_SOURCE_ROOT_UNREADABLE
DB_SOURCE_ROOT_SYMLINK_OUTSIDE
```

A root-contract failure must make DB inventory and DB CLI reports untrusted and exit `2`; do not scan only the remaining roots.

---

## Required integration changes

Replace executable hard-coded root selection in all DB-guard paths.

Audit and update:

- `scripts/db_guard/room_inventory.py`;
- `scripts/db_guard/declaration_scanner.py`;
- `scripts/db_guard/scanner.py`;
- GR-01 v2 source evidence;
- legacy source-evidence compatibility wrapper;
- `scripts/verify_db_access_boundaries.py`;
- `scripts/migrate_db_policy_signatures.py`;
- diagnostics/path validation that currently assumes only `app/src/...`;
- relevant Gradle DB task input validation;
- GR-00 evidence input manifest.

### Important implementation rules

1. APIs should receive repository root plus `SourceRootSet`, not a single Java directory.
2. Walking order must be deterministic:
   - manifest root order;
   - canonical repository-relative file path order.
3. Duplicate canonical source paths, unreadable roots, symlink escapes, and ambiguous source files fail closed.
4. Policy paths must validate against declared roots, not an in-code tuple.
5. Generic parser syntax validation may remain generic; authorization/root membership belongs in `source_roots.py`.
6. Do not weaken diagnostic-path safety merely to support modules. Expand it carefully to safe repository-relative production paths and test it.
7. The candidate generator from GR-02 must use the same manifest-backed root set after this PR.
8. Add the manifest as an explicit required DB guard input in Gradle and GR-00 evidence capture.
9. Do not create a second hard-coded source-root list in registry or static-suite code.

---

## Real Room inventory proof

Add a production integration test that:

1. loads the checked-in manifest;
2. runs the topology verifier against the real repository;
3. builds Room inventory from repository root, not a manually constructed Java root;
4. asserts no root-contract diagnostics;
5. verifies every discovered DAO and mutator path belongs to a declared root;
6. verifies a known real DAO, currently `ExpenseDao`, is discovered exactly once at its canonical path;
7. verifies the inventory is deterministic across two runs;
8. does not use a hard-coded DAO count.

Also add synthetic fixtures proving:

- a DAO in `src/main/java` is found;
- a DAO in `src/main/kotlin` is found when declared;
- an undeclared Kotlin root fails before partial inventory is trusted;
- test/debug/androidTest DAO fixtures are excluded;
- two modules with declared conventional roots are both scanned;
- a duplicate DAO FQCN across roots fails closed;
- source symlinks escaping the project fail closed.

---

## Implementation sequence

### Step 1 — Freeze current topology

Record:

```bash
git rev-parse HEAD
find . -path '*/src/main/java' -o -path '*/src/main/kotlin'
git grep -n "app/src/main/java" -- scripts config docs app/build.gradle.kts
```

Classify each match as:

- executable root-selection logic;
- policy/manifest data;
- test fixture;
- documentation.

### Step 2 — Add strict manifest loader and topology verifier

Write loader tests before changing Room inventory. Prove malformed root config cannot fall back to Java root.

### Step 3 — Migrate Room inventory

Replace `_PRODUCTION_SOURCE_ROOTS` and `_approved_root` behavior with shared root resolution.

Requirements:

- inventory scans all declared roots;
- paths remain repository-relative;
- root failure clears trust;
- RawQuery policy behavior remains unchanged;
- output ordering remains deterministic.

### Step 4 — Migrate declaration scanner and D4 scanner

Replace `_ROOT` and all single-root assumptions. Ensure declaration ranges, source reads, DAO resolution, and diagnostic paths work from multiple roots.

### Step 5 — Migrate policy evidence and candidate generation

Use root-aware canonical file resolution for:

- legacy policy evidence;
- GR-01 v2 evidence;
- GR-02 candidate source lookup.

No policy path may resolve by stripping a fixed `app/src/main/java` prefix.

### Step 6 — Wire DB control-plane inputs

Add `production_source_roots.yml` to:

- Gradle DB guard required input validation;
- GR-00 capture input manifest;
- any DB command configuration that hashes or validates policy inputs.

Do not redesign registry/suite ownership in this PR.

### Step 7 — Documentation and literal audit

Update DB ownership / guard docs to distinguish:

- declared production roots;
- excluded test/generated roots;
- root manifest versus Room schema baseline;
- root-contract failure versus architecture violation.

Run a final literal audit. Remaining hard-coded `app/src/main/java` references may exist only in checked-in policy data, documentation, or intentionally explicit fixtures—not executable root selection.

---

## Required test matrix

### Manifest/model tests

1. valid single root;
2. valid multiple modules;
3. valid Java plus Kotlin roots;
4. missing/schema version wrong;
5. unknown top-level/root keys;
6. invalid module;
7. source set not `main`;
8. absolute/backslash/traversal path;
9. root below a source root rather than the root itself;
10. test/debug/release/generated root;
11. duplicate root;
12. overlapping root;
13. missing directory;
14. symlink root;
15. noncanonical ordering;
16. unsupported dynamic module layout;
17. unsupported main source-set customization.

### Scanner/inventory tests

1. Java-root DAO discovered;
2. Kotlin-root DAO discovered;
3. both roots scanned in stable order;
4. undeclared Kotlin root yields controlled failure;
5. no partial trusted inventory after root failure;
6. test/androidTest/debug/release fixtures excluded;
7. declaration scanner and Room inventory agree on root membership;
8. policy evidence resolves a Kotlin-root policy path;
9. GR-02 candidate generator resolves a Kotlin-root source file;
10. duplicate FQCN across roots fails closed;
11. root symlink escape fails closed;
12. unchanged current-repository candidate regenerates identically.

### Real repository proof

1. manifest topology verification passes;
2. Room inventory has no root diagnostics;
3. all inventory paths are under manifest roots;
4. `ExpenseDao` appears exactly once;
5. two inventory runs have equal semantic content;
6. inventory-only CLI returns trusted report, no diagnostics, exit `0`.

---

## Required post-completion checks

```bash
python3 -m pytest \
  scripts/ci/test_verify_production_source_roots.py \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_declaration_scanner.py \
  scripts/test_db_guard_scanner_d4.py \
  scripts/test_migrate_db_policy_signatures.py \
  scripts/test_verify_db_access_v2.py \
  scripts/test_verify_db_access_boundaries.py \
  scripts/test_db_guard_policy_v2.py \
  scripts/test_db_guard_policy_v2_evidence.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_production_source_roots.py \
  --root . \
  --manifest config/guards/production_source_roots.yml
```

```bash
python3 scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-debug/gr03/inventory.json \
  --dump-room-mutators build/guard-debug/gr03/room-mutators.json
```

Expected: exit `0`, trusted report, no diagnostics.

```bash
python3 scripts/migrate_db_policy_signatures.py \
  --write-candidate \
  --policy config/guards/db_ownership_policy.yml \
  --output build/guard-debug/gr03/candidate.yml \
  --report build/guard-debug/gr03/migration-report.json || test $? -eq 1

cmp \
  build/guard-debug/gr03/candidate.yml \
  config/guards/db_ownership_policy.signatures.candidate.yml
```

```bash
git grep -n "app/src/main/java" -- scripts \
  | tee build/guard-debug/gr03/root-literal-audit.txt
```

Manually review every result. No executable DB root-selection code may retain a private Java-only root list.

Run normal DB CLI/ratchet/Gradle through GR-00 capture. Expected transitional state: active policy remains blocked; inventory-only must remain trusted.

### Preservation checks

```bash
git diff --exit-code -- \
  config/baselines/db_access.json \
  config/guards/db_ownership_policy.yml \
  config/guards/db_ownership_policy.signatures.candidate.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  app/src/main
```

The new source-root manifest and narrowly required guard-control-plane inputs are allowed changes.

---

## Definition of done

GR-03 is complete only when:

- one strict manifest declares actual production Kotlin roots;
- all DB guard subsystems consume the same root contract;
- undeclared production Kotlin roots fail closed;
- no partial inventory is trusted after root validation failure;
- actual Room inventory is proven against the manifest;
- candidate regeneration remains deterministic;
- root manifest is included in DB guard/evidence inputs;
- active policy, baseline, and production Kotlin remain unchanged;
- normal DB enforcement remains blocked only for the known later policy work.

## Required agent completion report

```text
PR: GR-03
START SHA:
END SHA:
FILES CHANGED:
DECLARED ROOTS:
OBSERVED ROOTS:
ROOT META-GUARD RESULT:
REAL ROOM INVENTORY RESULT:
ACTIVE POLICY CHANGED: no
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no
CANDIDATE REPRODUCIBLE: yes/no
COMMANDS RUN:
RESULTS:
UNEXPECTED ROOT LITERALS:
EXPECTED BLOCKED STATE:
NEXT PR: GR-04
```