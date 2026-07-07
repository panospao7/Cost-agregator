# Guard Framework Standard

> **Created**: PR 4 — Guard Framework Standardization
> **Purpose**: Defines the standard contract every architecture guard script must follow, the allowlist format, and the process for creating new guards.

---

## Overview

Every architecture guard script in this project follows a standard contract to ensure consistency, testability, and CI enforceability. This document serves as the definitive reference for guard authors, reviewers, and CI maintainers.

---

## Guard Script Requirements

| Requirement | Description |
|---|---|
| Rule ID | Unique identifier (e.g. `G-DB-01`, `G-PRIV-03`) |
| Description | What the guard enforces in one sentence |
| Scope | Which directories/files are scanned |
| `--fail-on-violation` | Flag that makes violations exit with code 1 |
| Deterministic output | Same input → same output |
| Exit code 0 on pass | No violations found |
| Exit code 1 on violation | Violations found and `--fail-on-violation` set |
| Exit code 2 on error | Script error (missing files, bad config, etc.) |
| Positive fixtures | Test files that should PASS the guard |
| Negative fixtures | Test files that should FAIL the guard |
| Allowlist support | Optional YAML allowlist with structured entries |

### Exit Code Semantics

| Exit Code | Meaning |
|-----------|---------|
| 0 | PASS — no violations found |
| 1 | FAIL — violations found and `--fail-on-violation` was set |
| 2 | ERROR — script cannot execute (missing source dir, broken config, etc.) |

---

## Output Format

```
RULE_ID path/to/File.kt:line_number violation_message
```

### Example

```
G-PRIV-01 app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudProvider.kt:42 Cloud provider reading AiSettings.redactBeforeCloud — must use CloudPayloadPolicy instead
```

### Hints

Guards may optionally emit a remediation hint as a subsequent line:

```
RULE_ID path/to/File.kt:line_number violation_message
  Hint: Use CloudPayloadPolicy.prepare() instead
```

---

## Allowlist Format (YAML)

All allowlists MUST use YAML format (migration from plain-text formats is in progress — see [Migration Path](#migration-path)).

```yaml
# Individual entry format
- rule: RULE_ID
  path: app/src/main/java/com/example/File.kt
  symbol: SomeClass.someMethod
  reason: "Why this is safe — REQUIRED"
  owner: "@github-handle — REQUIRED"
  expires: "YYYY-MM-DD — REQUIRED for non-permanent entries"
  linked_issue: "MIT-### — RECOMMENDED"
```

### Acceptable reason formats

Reasons may be:
- A short technical justification (`"canonical transaction lifecycle writer — guards at entry via withTransaction"`)
- A link to an issue/epic (`"approved maintenance/backfill methods — each calls writeBarrier"`)
- MUST be non-empty and describe why the entry is safe, not just what it does

---

## Allowlist Policy

- **Every allowlist entry MUST have**: `reason`
- **Every allowlist entry SHOULD have**: `owner`
- **Non-permanent entries MUST have**: `expires` (ISO 8601 date: `YYYY-MM-DD`)
- **Expired entries fail CI**: the `verify_allowlist_compliance.py` guard enforces this
- **Entries without `reason` fail CI**
- **Entries without `owner`**: warning only until **2026-10-01** (grace period); fail after that date
- **Allowlist changes require code owner review** (per CODEOWNERS when added)

---

## Existing Guards

| Guard Script | Rule IDs | Allowlist | Has Tests |
|---|---|---|---|
| `verify_privacy_boundaries.py` | G1–G14 | Inline (no file) | No dedicated file |
| `verify_db_access_boundaries.py` | DB-ACCESS | `config/db_access_allowlist.yml` | ✅ `scripts/test_verify_db_access_boundaries.py` |
| `verify_event_writers.py` | EVENT-WRITER | `scripts/event_writer_allowlist.txt` | No dedicated file |
| `verify_money_boundaries.py` | G-MONEY-02, G-MONEY-10–G-MONEY-21 | Inline (comment-based) | No dedicated file |
| `verify_source_provenance_boundaries.py` | G-PROV-01–G-PROV-05 | Inline (no file) | No dedicated file |
| `verify_allowlist_compliance.py` | ALLOWLIST-COMPLIANCE | Meta-guard (validates all allowlists) | No dedicated file |
| `verify_migration_matrix.py` | G-MIG-01 | N/A (no allowlist) | ✅ `scripts/test_verify_migration_matrix.py` |
| `verify_cancellation_boundaries.py` | G-CANCEL-01 | `scripts/allowlists/cancellation_allowlist.yml` | ✅ `scripts/test_verify_cancellation_boundaries.py` |
| `verify_ui_dao_boundaries.py` | G-UI-DAO-01 | `scripts/allowlists/ui_dao_allowlist.yml` | ✅ `scripts/test_verify_ui_dao_boundaries.py` |
| `verify_worker_boundaries.py` | G-WORKER-01 | `scripts/allowlists/worker_allowlist.yml` | ✅ `scripts/test_verify_worker_boundaries.py` |
| `verify_receipt_link_boundaries.py` | G-RCPT-LINK-01 | `scripts/allowlists/receipt_link_allowlist.yml` | ✅ `scripts/test_verify_receipt_link_boundaries.py` |
| `verify_import_lifecycle_boundaries.py` | G-IMPORT-01 | `scripts/allowlists/import_lifecycle_allowlist.yml` | ✅ `scripts/test_verify_import_lifecycle_boundaries.py` |
| `verify_cloud_payload_boundaries.py` | G-CLOUD-01 | `scripts/allowlists/cloud_payload_allowlist.yml` | ✅ `scripts/test_verify_cloud_payload_boundaries.py` |
| `verify_pii_logging_boundaries.py` | G-PII-01 | `scripts/allowlists/pii_logging_allowlist.yml` | ✅ `scripts/test_verify_pii_logging_boundaries.py` |
| `verify_di_release_boundaries.py` | G-DI-01 | `scripts/allowlists/di_release_allowlist.yml` | ✅ `scripts/test_verify_di_release_boundaries.py` |
| `verify_ignored_test_budget.py` | G-IGNORE-01 | `config/release_block_denylist.yml` (denylist, not allowlist) | ✅ `scripts/test_verify_ignored_test_budget.py` |

---

## Related Documents

- **Developer Quickstart**: `docs/ci/developer-quickstart.md` — pre-push checklist, guard script commands, allowlist format, and CI pipeline overview
- **Baseline Inventory**: `docs/ci/CI_GUARDRAILS_BASELINE.md` — full inventory of all guardrails, migration matrix, and ignored test budget
- **Local CI Guide**: `docs/ci/local-ci.md` — local reproduction commands for every CI job

---

## CODEOWNERS

Architecture-sensitive paths in the repository have assigned code owners via the `CODEOWNERS` file (repo root). This ensures that changes to guarded areas receive automatic review requests.

### Covered paths

| Path pattern | Area |
|---|---|
| `.github/workflows/` | CI workflow files |
| `scripts/verify_*.py`, `scripts/test_verify_*.py` | Guard scripts and their tests |
| `scripts/allowlists/` | Guard allowlists |
| `scripts/guard_template.py` | Guard template |
| `scripts/verify_allowlist_compliance.py` | Allowlist compliance meta-guard |
| `config/release_block_denylist.yml` | Release-block denylist |
| `docs/ci/` | CI documentation |
| `app/src/main/java/**/database/` | Room database |
| `app/schemas/` | Room schema snapshots |
| `app/src/main/java/**/lifecycle/` | Lifecycle services |
| `app/src/main/java/**/coordinator/` | Transaction/recurring coordinators |
| `app/src/main/java/**/workers/` | WorkManager workers |
| `app/src/main/java/**/privacy/` | Privacy enforcement |
| `app/src/main/java/**/security/` | Security enforcement |

All paths are assigned to `@panospao7`.

### How CODEOWNERS affects guard development

- **Allowlist changes** (`scripts/allowlists/`) require code owner review
- **New guard scripts** (`scripts/verify_*.py`) require code owner review
- **CI workflow changes** (`.github/workflows/`) require code owner review
- **Guard framework docs** (`docs/ci/`) require code owner review

---

## Migration Path

Existing allowlists use older formats. Migration plan:

### 1. `config/db_access_allowlist.yml`

Current format uses `reason` and `allowed_until` (descriptive strings). Migration steps:
- Add `owner` field to each entry (grace period until 2026-10-01)
- Convert `allowed_until` descriptive strings to `expires` dates where applicable
- Add `linked_issue` field where relevant

### 2. `scripts/event_writer_allowlist.txt`

Current format: plain-text with inline `# reason` comments.
- Migrate to YAML format
- Add `owner`, `expires`, `linked_issue` fields
- Non-breaking: old `.txt` format continues to work during transition

### 3. Inline allowlists (comment-based)

Guards like `verify_money_boundaries.py` and `verify_privacy_boundaries.py` use inline comment-based allowlists:
```kotlin
// G-MONEY-ALLOW[CURR-123][G-MONEY-17]: legacy compat path
```

- Extract to YAML files where practical
- Comment-based format remains valid for tight-coupling guards (e.g., `G-MONEY` rules that are co-located with the violation)

---

## Creating a New Guard

1. **Copy `scripts/guard_template.py`** to `scripts/verify_<name>.py`
2. **Set `RULE_ID`**, `DESCRIPTION`, `SCOPE_DIRS`, `FILE_PATTERNS`
3. **Implement `scan_file()`** with your detection logic
4. **Create positive/negative fixture files** in `scripts/fixtures/`
5. **Write pytest tests** in `scripts/test_<guard_name>.py`
6. **Add to `static-guards` CI job** in `.github/workflows/ci.yml`
7. **Update this document** — add your guard to the [Existing Guards](#existing-guards) table
8. **Update `docs/ci/local-ci.md`** — add the local reproduction command
9. **Update `docs/ci/CI_GUARDRAILS_BASELINE.md`** — add to the guard scripts table

### Checklist for new guards

- [ ] `RULE_ID` is unique (grep existing guard scripts to confirm)
- [ ] `DESCRIPTION` is a single sentence
- [ ] `SCOPE_DIRS` is scoped to the minimum required directories
- [ ] `--fail-on-violation` flag is supported
- [ ] Exit codes follow the standard (0=pass, 1=fail, 2=error)
- [ ] Positive fixtures exist (files that should pass the guard)
- [ ] Negative fixtures exist (files that should fail the guard)
- [ ] Allowlist support (if applicable) uses YAML format
- [ ] Tests pass locally: `python -m pytest scripts/test_<name>.py -v`
- [ ] CI step added to `.github/workflows/ci.yml` `static-guards` job
- [ ] Documentation updated
