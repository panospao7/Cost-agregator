# Guard Finding DB V2 Migration Ledger

> **Historical record** — this ledger is a frozen PR-D migration snapshot, not current-state authority. as-of: start SHA `bb2a6f18f12300af60ce1db475fb0c8d73f6b774` (branch `guard-finding-db-discovery-v2`); scope: P0-1..P0-5 migration bookkeeping only; it is not evidence for current HEAD. Current state authority: docs/ci/GUARD_EVIDENCE_INDEX.yml and docs/ci/DB_ACCESS_V2_RATCHET_DEBT.md.

**Plan:** `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md`
**Branch:** `guard-finding-db-discovery-v2`
**Start SHA:** `bb2a6f18f12300af60ce1db475fb0c8d73f6b774`
**Scope:** P0-1 through P0-5 only
**Status:** PARTIAL / PENDING REVIEW

---

## 1. Scope boundaries

| Item | In scope | Out of scope |
|------|----------|--------------|
| P0-1: Replace text-derived DB fingerprints with versioned structured finding contract | YES | |
| P0-2: Replace coarse rule-plus-file ratchet identities with symbol-level, multiplicity-aware identities | YES | |
| P0-3: Derive DAO mutators from Room declarations and SQL semantics | YES | |
| P0-4: Stop excluding files solely because filename ends in `Dao.kt` | YES | |
| P0-5: Authorize exact overload signatures rather than unions of same-name methods | YES | |
| time-boundary cleanup | | NO |
| barrier dominance | | NO |
| worker/helper mediation | | NO |
| PSI migration | | NO |
| migration PR gate | | NO |

---

## 2. Current state snapshot

### 2.1 Baseline (`config/baselines/db_access.json`)

- **Format:** Legacy v1 — flat JSON object with a `"guard"`, `"generated"` timestamp, and a `"fingerprints"` array of human-readable text strings. No `schema_version`, no `fingerprint_schema_version`, no count/multiplicity field.
- **Entry count:** **15 entries** (fingerprint strings)
- **Generated timestamp:** `2026-07-10T22:03:53.298282+00:00`
- **Last committed at SHA:** `8589c2d5` (2026-07-11, pre-scanner rewrite)
- **Baseline identity scheme:** `RULE_FILE_PATH` — coarse file-level fingerprints such as:
  - `FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class app/src/.../DatabaseMigrations.kt`
  - `UNALLOWLISTED_CLASS app/src/.../SomeFile.kt`
  - `UNALLOWLISTED_CLASS_DIRECT_CHAIN app/src/.../SomeFile.kt`
- **No per-method, per-DAO, per-operation, or per-signature granularity** in baseline identity.

### 2.2 Scanner and report format

- **Scanner:** `scripts/verify_db_access_boundaries.py` (last behavioral change `6f0e46c8`, 2026-08-07)
- **Output format:** Legacy text/line-based — human-readable diagnostics parsed by regex in the ratchet
- **Fingerprint extraction:** `guard_ratchet.py` uses `extract_fingerprints()` with regex patterns (`_PATH_LINE_RE`, `_BRACKET_RULE_RE`, `_STANDARD_RE`, `_MONEY_RULE_RE`) to parse stdout text
- **No structured JSON report** emitted by the scanner in v1 mode
- **Scanner emits detailed per-(class, method, dao, op) text lines** such as:
  ```
  UNALLOWLISTED_CLASS: no exact policy entry for class=X method=Y dao=Z op=W rule=db_ownership_policy app/...
  ```

### 2.3 Ownership policy (`config/guards/db_ownership_policy.yml`)

- **Format:** YAML, H2-exact contract (path/class/method/daos/operation/barrier fields per entry)
- **No signature-level identity** — entries keyed by `path + class + method` only (no receiver, no parameter types)
- **Overload authorization:** implicit union of all methods with the same name within a class
- **No change planned or made in Phase 0**

### 2.4 Structural exceptions (`config/guards/db_structural_exceptions.yml`)

- **No change planned or made in Phase 0**

---

## 3. Known validation status

> Source: `VALIDATION_FINDINGS_2026-08-09.md` (Update 1 and later)

| Metric | Value | Source |
|--------|-------|--------|
| DB findings (current scanner) | **385** | static guard suite, `db_access` guard |
| Baseline entries (v1) | **15** | `config/baselines/db_access.json` |
| Baseline keys resolved (ratchet) | **15** (all) | scanner/policy mismatch — format drift |
| New finding keys | **385** (all) | scanner upgraded, baseline not re-synced |
| Static guard suite overall | **RED** (exit 1) | 3 blocking violations: `time_boundaries`, `db_access`, `guard_tests` (guard_tests now PASS post-fix) |
| Registry check | **PASS** (exit 0) | 18 guards registered, consistent |
| pytest (post-fix) | **454 passed, 5 skipped** | 5 original failures fixed in Update 1 |
| Root cause of 385 count | **Baseline/fingerprint format drift** | v1 baseline committed 2026-07-11 in G2/H1 coarse format; scanner rewritten 2026-08-05/07 with per-(class,method,dao,op) detail; two formats share zero overlap |

### Finding type breakdown (385 current findings)

| Rule | Count |
|------|-------|
| `UNALLOWLISTED_CLASS` | ~386 |
| `UNSUPPORTED_EXPRESSION_BODY` | 6 |
| `FORBIDDEN_FILE_OP` | 4 |
| `UNSUPPORTED_DAO_SCOPE` | 2 |
| `UNALLOWLISTED_CLASS_DIRECT_CHAIN` | 1 |
| `UNSUPPORTED_METHOD_BODY` | 1 |

### Representative findings

- `UNALLOWLISTED_CLASS no exact policy entry for class=WarrantyTrackerRepository method=markWarrantyAsClaimed dao=warrantyLifecycleEventDao op=insert`
- `UNALLOWLISTED_CLASS no exact policy entry for class=WorkerRunLoggerImpl method=terminal dao=backgroundJobRunDao op=completeTerminal`
- `FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class DatabaseMigrations.kt`
- `UNSUPPORTED_DAO_SCOPE: DAO mutation outside a resolved method (scope-format .. top-level dao=receiptEventDao op=insert) ReceiptSideEffectPlanner.kt`

---

## 4. Phase 0 freeze commands

All commands below are **NOT RUN IN THIS RECOVERY WORKTREE** because validation execution is pending. Exit codes and log paths will be appended when commands are executed manually.

### 4.1 pytest (guard test suite)

```bash
python -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  scripts/ci/test_guard_ratchet.py \
  -v --tb=short \
  2>&1 | tee build/guard-v2/before/tests.log
```

- **Status:** NOT RUN IN THIS RECOVERY WORKTREE
- **Expected result:** Red (known 385-finding drift + T4C time holds)
- **Exit code:** PENDING
- **Log path:** `build/guard-v2/before/tests.log` (not yet created)

### 4.2 DB guard script

```bash
python scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  2>&1 | tee build/guard-v2/before/db-guard.log
```

- **Status:** NOT RUN IN THIS RECOVERY WORKTREE
- **Expected result:** Red (385 findings)
- **Exit code:** PENDING
- **Log path:** `build/guard-v2/before/db-guard.log` (not yet created)

### 4.3 Guard ratchet

```bash
python scripts/ci/guard_ratchet.py \
  --guard-name db_access \
  --command-arg=python \
  --command-arg=scripts/verify_db_access_boundaries.py \
  --command-arg=--fail-on-violation \
  --baseline config/baselines/db_access.json \
  --fail-on-violation \
  --ci-mode \
  --output-summary build/guard-v2/before/ratchet-summary.json \
  2>&1 | tee build/guard-v2/before/ratchet.log
```

- **Status:** NOT RUN IN THIS RECOVERY WORKTREE
- **Expected result:** Red (385 new, 15 resolved, 0 unchanged)
- **Exit code:** PENDING
- **Log path:** `build/guard-v2/before/ratchet.log` (not yet created)
- **Summary path:** `build/guard-v2/before/ratchet-summary.json` (not yet created)

### 4.4 Freeze source artifacts (copy commands)

```bash
cp config/baselines/db_access.json \
  build/guard-v2/before/db_access_v1.json

cp config/guards/db_ownership_policy.yml \
  build/guard-v2/before/db_ownership_policy.yml

cp config/guards/db_structural_exceptions.yml \
  build/guard-v2/before/db_structural_exceptions.yml
```

- **Status:** NOT RUN IN THIS RECOVERY WORKTREE
- **Artifact paths (pending):**
  - `build/guard-v2/before/db_access_v1.json`
  - `build/guard-v2/before/db_ownership_policy.yml`
  - `build/guard-v2/before/db_structural_exceptions.yml`

---

## 5. Before-artifact paths

All paths under `build/guard-v2/before/` are **pending** — directory and files have not yet been created in this worktree.

| Artifact | Path | Status |
|----------|------|--------|
| V1 baseline frozen copy | `build/guard-v2/before/db_access_v1.json` | NOT CREATED |
| Ownership policy frozen copy | `build/guard-v2/before/db_ownership_policy.yml` | NOT CREATED |
| Structural exceptions frozen copy | `build/guard-v2/before/db_structural_exceptions.yml` | NOT CREATED |
| pytest log | `build/guard-v2/before/tests.log` | NOT CREATED |
| DB guard log | `build/guard-v2/before/db-guard.log` | NOT CREATED |
| Ratchet log | `build/guard-v2/before/ratchet.log` | NOT CREATED |
| Ratchet summary | `build/guard-v2/before/ratchet-summary.json` | NOT CREATED |

---

## 6. Changes not begun

| PR | Description | Status |
|----|-------------|--------|
| PR-F1 | Shared finding protocol | **PARTIAL / PENDING REVIEW** — v2 model (`guard_findings.py`), catalog (`finding_rule_catalog.py`), and test file (`test_guard_findings.py`) authored; covers v2 envelope (schema, schema_version, guard, findings, diagnostics, statistics — no tool/fingerprint_profile/created_at), deep immutability (`FrozenDict` recursive freeze, deterministic hashing), privacy/sanitized errors (no raw paths, exception text, or hostile values leak), multiplicity-aware aggregation and fingerprint fixes (distinct-location survival, exact-duplicate rejection), unknown-symbol diagnostics (`unresolved_symbol_diagnostic()` / `ProtocolFailure` / `UNRESOLVED_SYMBOL_BLOCKING` / `UNKNOWN_RULE`), declared-order fingerprint exact string and identity order, and unknown-guard/schema/version read-path precedence before content materialization; tests authored but **NOT EXECUTED** in recovery worktree; **not yet reviewed** |
| PR-F2 | Ratchet v2 and count-aware comparison | **PARTIAL** — v1 baseline F2 migration-blocker, registry protocol auto-resolve, protocol-v2 --command-arg suite integration |
| PR-D1 | Exact callable signature model | **PARTIAL / PENDING REVIEW** — signature model, parser, migration CLI, and candidate artifact implemented; tests and runtime validation not executed; final review pending |
| PR-D2 | Room-derived mutator inventory | **PARTIAL / PENDING REVIEW** — Room inventory implemented; validation pending |
| PR-D3 | Declaration-level Dao.kt scanning | NOT BEGUN |
| PR-D4 | Structured DB finding output | **PARTIAL** — suite integration with protocol-v2 --command-arg tokens and canonical policy paths |
| PR-D5 | Classify findings and migrate DB baseline | NOT BEGUN |
| PR-F3 | Migrate remaining ratcheted guards | NOT BEGUN |

No baseline changes have been made. No ownership-policy changes have been made. No structural-exceptions changes have been made.

---

## 7. Next safe step

1. **Run PR-F1 tests** (not yet executed in this recovery worktree):
   ```bash
   python -m pytest scripts/ci/test_guard_findings.py -v --tb=short
   ```
2. Run strict code review on `guard_findings.py`, `finding_rule_catalog.py`, and `test_guard_findings.py` against the protocol spec in `docs/ci/GUARD_FINDING_PROTOCOL.md`.
3. Execute Phase 0 freeze commands manually (Section 4) if freeze evidence is still needed.
4. Only after PR-F1 review + runtime validation passes, proceed to PR-F2 (ratchet v2 consumption and count-aware comparison).

---

## 8. Summary status

| Gate | Status |
|------|--------|
| Phase 0 freeze commands executed | **NOT RUN** |
| Before artifacts captured | **NOT CREATED** |
| Baseline unchanged | **YES** (confirmed) |
| Ownership policy unchanged | **YES** (confirmed) |
| Structural exceptions unchanged | **YES** (confirmed) |
| PR-D1 through PR-D5 begun | **PARTIAL** — PR-D1 and PR-D2 partial/pending review (see Section 6); PR-D3 not begun; PR-D4 partial (suite integration); PR-D5 not begun |
| PR-F1 begun | **PARTIAL / PENDING REVIEW** — model, catalog, and test file authored (v2 envelope, deep immutability/`FrozenDict`, privacy/sanitized errors, multiplicity/fingerprint fixes, unknown-symbol diagnostics, declared-order fingerprint exact string, read-path precedence); pending strict review and runtime validation |
| PR-F1 tests executed | **NOT RUN** in recovery worktree; `python -m pytest scripts/ci/test_guard_findings.py -v` must be run to validate |
| PR-F2 begun | **PARTIAL** — v1 baseline F2 migration-blocker, registry protocol auto-resolve, protocol-v2 --command-arg suite integration; count-aware comparison already implemented in guard_ratchet.py |
| Ledger complete with exit codes | **NO** (pending manual execution) |

**Overall:** PARTIAL / PENDING REVIEW — PR-F1 implementation (`guard_findings.py`, `finding_rule_catalog.py`, `test_guard_findings.py`) is authored (v2 envelope, deep immutability/FrozenDict, privacy/sanitized errors, multiplicity/fingerprint fixes, unknown-symbol diagnostics, declared-order fingerprint exact string, unknown-guard/schema/version read-path precedence) but has **not been reviewed or executed** in this recovery worktree. PR-D1 (exact callable signature model, parser, migration CLI, candidate artifact) and PR-D2 (Room-derived mutator inventory) are implemented but pending tests, runtime validation, and review. PR-D4 (suite integration) is partial — db_access guard now uses protocol-v2 `--command-arg` tokens with canonical policy paths. PR-F2 is partial — v1 baseline F2 migration-blocker (`RATCHET_V1_BASELINE_INCOMPATIBLE`), registry protocol auto-resolve for `db_access`, and protocol-v2 suite command integration. The candidate artifact contains **9 resolved findings and 90 unresolved findings** and is **non-authorizing** (it does not gate CI or alter baseline enforcement). Phase 0 freeze commands remain defined but not yet executed. The DB baseline (`config/baselines/db_access.json`) and ownership policy (`config/guards/db_ownership_policy.yml`) remain **unchanged** (v1 format, causes controlled F2 migration-blocker exit 2 until v2 baseline migration).
