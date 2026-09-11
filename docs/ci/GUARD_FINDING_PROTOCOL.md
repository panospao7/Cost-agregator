# Guard Finding Protocol v2

**Schema name:** `cost-aggregator.guard-findings`  
**Schema version:** 2  
**Scope:** PR-F1 — shared finding contract for all ratcheted guards  
**Status:** PENDING — protocol defined, implementation not yet complete  
**Related plan:** `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` §3, §6  
**Ledger:** `docs/ci/GUARD_FINDING_DB_V2_LEDGER.md`

---

## 1. Purpose

This document defines the structured finding contract consumed by guard scripts, the ratchet, the static-suite reporter, and the Gradle integration. Every migrated guard emits protocol-v2 JSON reports. The ratchet never parses human-readable stdout in v2 mode.

The protocol replaces text-derived fingerprints with versioned, semantically stable identities. This ensures that diagnostic wording changes and source line movement do not alter baseline identity.

---

## 2. Report envelope

Every guard writes exactly one JSON report per execution:

```json
{
  "schema": "cost-aggregator.guard-findings",
  "schema_version": 2,
  "guard": "<guard-name>",
  "findings": [],
  "diagnostics": [],
  "statistics": {}
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `schema` | string | yes | Must be `"cost-aggregator.guard-findings"` |
| `schema_version` | integer | yes | Must be `2` |
| `guard` | string | yes | Must match the registered guard name |
| `findings` | array | yes | List of `GuardFinding` objects |
| `diagnostics` | array | yes | List of `GuardDiagnostic` objects |
| `statistics` | object | yes | Guard-specific counts (e.g. files scanned, DAOs discovered) |

Human-readable console output remains available for debugging, but the ratchet ignores it when `finding_protocol=2` in the guard registry entry.

---

## 3. Core models

All models are immutable dataclasses. Controlled values are drawn from the rule catalog and known enumerations. Diagnostic values (location, message) are for human reference only and never participate in fingerprint identity.

### 3.1 SourceLocation

```python
@dataclass(frozen=True)
class SourceLocation:
    line: int           # positive integer, ≥ 1
    column: Optional[int]  # positive integer or None
    end_line: Optional[int]  # positive integer or None
    end_column: Optional[int]  # positive integer or None
```

| Rule | Description |
|------|-------------|
| `line ≥ 1` | Zero or negative line numbers are rejected |
| Diagnostic only | `SourceLocation` never enters fingerprint identity |
| Bounded | All fields fit within Python `int`; no arbitrary precision |

### 3.2 CallableSymbol

```python
@dataclass(frozen=True)
class CallableSymbol:
    owner: str       # FQCN of the enclosing class/object
    name: str        # method/property/function name
    receiver: Optional[str]  # extension receiver FQCN or None
    parameters: List[str]    # ordered FQCN parameter types
    kind: str        # one of the allowed kind values
```

**Allowed `kind` values:**

| Value | Description |
|-------|-------------|
| `function` | Instance method |
| `constructor` | Constructor call |
| `property_getter` | Getter accessor |
| `property_setter` | Setter accessor |
| `top_level_function` | File-level function |
| `initializer` | `init` block or property initializer |
| `unknown` | Non-blocking discovery only |

**`unknown` usage rule:** `unknown` may be used only during non-blocking discovery scans. A blocking finding that requires symbol identity (e.g. `DB_UNAUTHORIZED_MUTATION`) must reject `unknown` symbols with exit code `2` and a `DB_SIGNATURE_UNRESOLVED` diagnostic.

### 3.3 GuardFinding

```python
@dataclass(frozen=True)
class GuardFinding:
    rule: str           # from rule catalog
    severity: str       # "error" | "warning"
    path: str           # canonical POSIX, repository-relative
    location: SourceLocation
    symbol: CallableSymbol
    identity: Dict[str, str]  # controlled values from rule profile
    message: str        # human-readable, diagnostic only
```

| Field | Participates in fingerprint | Constraints |
|-------|-----------------------------|-------------|
| `rule` | yes | Must exist in rule catalog |
| `severity` | no | `"error"` or `"warning"` |
| `path` | yes | POSIX, no absolute paths, no `..`, no backslashes |
| `location` | no | Diagnostic; line ≥ 1 |
| `symbol` | yes | All components bounded |
| `identity` | yes | Controlled scalars; keys from rule profile |
| `message` | no | Diagnostic; bounded length |

### 3.4 GuardDiagnostic

```python
@dataclass(frozen=True)
class GuardDiagnostic:
    code: str               # from diagnostic code catalog
    path: Optional[str]     # canonical POSIX or None
    symbol: Optional[str]   # controlled string or None
    controlled_context: Dict[str, str]  # bounded key-value pairs
```

**Diagnostics are never baseline-able.** They represent infrastructure/parser failures and must never appear in baseline entries. If any diagnostic exists in a report, the guard must exit `2`.

### 3.5 GuardRunReport

```python
@dataclass(frozen=True)
class GuardRunReport:
    schema: str
    schema_version: int
    guard: str
    findings: List[GuardFinding]
    diagnostics: List[GuardDiagnostic]
    statistics: Dict[str, Any]
```

Provides the complete validated report object. Serialization and atomic writing are described in §7.

### 3.6 FingerprintProfile

```python
@dataclass(frozen=True)
class FingerprintProfile:
    guard: str
    identity_fields: Tuple[str, ...]
    multiplicity: str  # "count"
```

Defines which fields from `GuardFinding` participate in the fingerprint for a given rule. The profile is registered in the rule catalog (§4).

### 3.7 AggregatedFinding

```python
@dataclass(frozen=True)
class AggregatedFinding:
    fingerprint: str
    count: int
    rule: str
    locations: List[SourceLocation]
```

Used by the ratchet for comparison. The `fingerprint` is the stable identity string. The `count` tracks multiplicity. `locations` are informational only and do not affect baseline identity.

---

## 4. Rule catalog

Each stable rule is registered with its required identity fields. The catalog is defined in `scripts/ci/finding_rule_catalog.py`.

### 4.1 Policy rules (baseline-able)

| Rule | Required identity fields |
|------|--------------------------|
| `DB_UNAUTHORIZED_MUTATION` | `path`, `symbol.owner`, `symbol.name`, `symbol.receiver`, `symbol.parameters`, `identity.dao`, `identity.accessor`, `identity.operation`, `identity.mutation_kind`, `identity.call_form` |
| `DB_MISSING_WRITE_BARRIER` | `path`, `symbol.owner`, `symbol.name`, `symbol.receiver`, `symbol.parameters`, `identity.dao`, `identity.operation` |
| `DB_FORBIDDEN_STRUCTURAL_OPERATION` | `path`, `symbol.owner`, `symbol.name`, `identity.operation` |

### 4.2 Infrastructure diagnostic codes (never baseline-able)

| Code | Description |
|------|-------------|
| `DB_SOURCE_UNREADABLE` | Source file cannot be read |
| `DB_METHOD_BODY_UNSUPPORTED` | Method body contains unsupported syntax |
| `DB_EXPRESSION_BODY_UNSUPPORTED` | Expression-body function not analyzable |
| `DB_DAO_SCOPE_UNRESOLVED` | DAO call scope cannot be determined |
| `DB_CALL_TARGET_AMBIGUOUS` | Call target resolution is ambiguous |
| `DB_POLICY_SOURCE_EVIDENCE_INVALID` | Policy entry cannot be verified against source |
| `DB_ROOM_QUERY_UNCLASSIFIABLE` | Room `@Query` SQL cannot be classified |
| `DB_SIGNATURE_UNRESOLVED` | Exact callable signature cannot be resolved |
| `DB_DAO_INHERITANCE_UNRESOLVED` | DAO inheritance chain is broken |

### 4.3 Unknown rule handling

A rule ID not present in the catalog is an **infrastructure failure**. The guard must:

1. Emit a `GuardDiagnostic` with a controlled code (e.g. `UNKNOWN_RULE`).
2. Exit with code `2`.
3. Never silently skip or downgrade the finding.

---

## 5. Canonical paths and controlled strings

### 5.1 Path rules

| Rule | Description |
|------|-------------|
| POSIX format | Forward slashes only; no backslashes |
| Repository-relative | No leading `/`; no absolute paths |
| No traversal | No `..` segments |
| Bounded length | Maximum 500 characters |
| No source root prefix stripping ambiguity | The `app/src/main/java/...` prefix is retained |

Windows paths are normalized to POSIX before serialization.

### 5.2 Controlled strings

All identity values, symbol components, and diagnostic context values must be **bounded controlled strings**:

| Field class | Maximum length |
|-------------|---------------|
| Path | 500 characters |
| Symbol component (owner, name, receiver, each parameter) | 300 characters |
| Identity scalar (dao, operation, mutation_kind, etc.) | 300 characters |
| Message | 500 characters |
| Diagnostic code | 100 characters |
| Findings per report | 100,000 maximum |

**Forbidden content in any string field:**

- Raw source code snippets
- Exception messages or stack traces
- SQL payload values or query text
- User financial data or PII
- Timestamps (except `generated_at` in the envelope)
- File system paths from exceptions
- Arbitrary `e.message` content

---

## 6. Deterministic ordering

### 6.1 Finding sort order

Findings within a report are sorted by a deterministic key to ensure stable output:

1. `rule` (lexicographic)
2. `path` (lexicographic)
3. `symbol.owner` (lexicographic)
4. `symbol.name` (lexicographic)
5. `symbol.parameters` (lexicographic, element-wise)
6. `identity.dao` (lexicographic)
7. `identity.operation` (lexicographic)

Same-input guarantees same-output regardless of scan order, file system enumeration order, or platform.

### 6.2 Canonicalization

`canonicalize_report(report)` applies the sort and normalizes all string values (e.g. path separators, whitespace trimming). The canonical form is what gets serialized and compared.

---

## 7. Fingerprint construction (v2)

### 7.1 Identity fields

The fingerprint is built **only** from fields listed in the rule's `FingerprintProfile.identity_fields`. Line numbers, column numbers, messages, timestamps, and source snippets are **excluded**.

### 7.2 Format

```text
v2|<guard>|<rule>|<key>=<value>|<key>=<value>|...
```

Example:

```text
v2|db_access|DB_UNAUTHORIZED_MUTATION|path=app%2Fsrc%2F...|symbol.owner=com.example.SomeRepository|symbol.name=save|symbol.receiver=%3Cnone%3E|symbol.parameters=%5B%22com.example.Expense%22%5D|identity.dao=com.example.ExpenseDao|identity.operation=insert|identity.mutation_kind=ROOM_INSERT
```

### 7.3 Encoding

| Character class | Encoding |
|-----------------|----------|
| `/` | `%2F` |
| `<none>` | `%3Cnone%3E` |
| `[`, `]` | `%5B`, `%5D` |
| `"` | `%22` |
| `|` (pipe) | `%7C` |
| `=` (in values) | `%3D` |
| `%` (literal) | `%25` |

Percent encoding is deterministic and collision-free for the bounded character set.

### 7.4 Fingerprint exclusions

The following fields must **never** participate in fingerprint identity:

- `line`
- `column`
- `end_line`
- `end_column`
- `message`
- `severity`
- Timestamps
- Report ordering
- Source snippets
- Diagnostic context

### 7.5 Fingerprint inclusions (semantic identity)

The fingerprint includes everything that defines *what* the finding is:

- Canonical path
- Full callable symbol (owner, name, receiver, parameter types)
- Identity fields from the rule profile (dao, operation, mutation_kind, etc.)

### 7.6 Collision resistance

- Parameter order changes the fingerprint (ordered list encoding).
- DAO operation changes the fingerprint.
- Extension receiver changes the fingerprint.
- Delimiter characters in values are percent-encoded to prevent injection.

---

## 8. Multiplicity and count aggregation

### 8.1 Count field

Each `AggregatedFinding` carries a `count` field:

```json
{
  "fingerprint": "v2|db_access|DB_UNAUTHORIZED_MUTATION|...",
  "count": 2,
  "rule": "DB_UNAUTHORIZED_MUTATION",
  "locations": [
    {"line": 42, "column": 5},
    {"line": 87, "column": 9}
  ]
}
```

### 8.2 Comparison semantics

| Current state | Baseline state | Ratchet result |
|---------------|----------------|----------------|
| New fingerprint key | absent | NEW_KEYS |
| Existing key, higher count | present | NEW_OCCURRENCES |
| Existing key, lower count | present | RESOLVED_OCCURRENCES |
| Existing key, same count | present | UNCHANGED_KEYS |
| Absent key | present | RESOLVED_KEYS |

The `count` prevents two same-rule findings in the same method from collapsing into one baseline entry. Locations are informational for display but do not define the baseline identity.

---

## 9. JSON validation

### 9.1 Report validation

`validate_report(report)` checks:

| Check | Failure action |
|-------|----------------|
| `schema == "cost-aggregator.guard-findings"` | exit 2 |
| `schema_version == 2` | exit 2 |
| `guard` matches registered guard name | exit 2 |
| `findings` is a list | exit 2 |
| `diagnostics` is a list | exit 2 |
| Each finding has all required fields | exit 2 |
| Each finding's rule exists in catalog | exit 2 |
| Path is canonical POSIX | exit 2 |
| No absolute paths, backslashes, or `..` | exit 2 |
| All string lengths within bounds | exit 2 |
| No duplicate exact source occurrences | exit 2 |
| Finding guard matches report guard | exit 2 |

### 9.2 Malformed JSON

If the report file cannot be parsed as valid JSON:

1. Emit a diagnostic.
2. Exit with code `2`.
3. Never fall back to stdout parsing.

### 9.3 Schema mismatch (baseline vs. report)

When the ratchet loads a baseline with `baseline_schema_version != 2` or `guard_output_schema_version != report.schema_version`:

1. Emit `RATCHET_BASELINE_SCHEMA_MISMATCH`.
2. Exit with code `2`.
3. Do not perform any new/resolved comparison.

---

## 10. Atomic write protocol

### 10.1 Sibling-temp write/flush/replace

All report writing uses atomic file replacement:

```python
def write_report_atomic(path: str, report: GuardRunReport) -> None:
    tmp_path = path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(canonicalize_report(report), f, indent=2, sort_keys=False)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp_path, path)
```

| Step | Purpose |
|------|---------|
| Write to `<path>.tmp` | Sibling temp file in same directory |
| `flush()` | Push data from Python buffer to OS buffer |
| `os.fsync()` | Ensure data reaches stable storage |
| `os.replace()` | Atomic rename on POSIX; near-atomic on Windows |

### 10.2 Failure semantics

If writing fails mid-stream:

- The original file (if any) is preserved.
- The `.tmp` file may be partially written; it is not the active baseline.
- The guard exits `2`.

No guard or ratchet may read a `.tmp` file.

---

## 11. Serialization API

| Function | Description |
|----------|-------------|
| `load_report(path)` | Parse JSON, validate schema, return `GuardRunReport` or raise |
| `write_report_atomic(path, report)` | Canonicalize, write to sibling temp, flush, fsync, replace |
| `validate_report(report)` | Check all invariants; raise on violation |
| `canonicalize_report(report)` | Sort findings, normalize paths, return canonical form |

---

## 12. Forbidden content

The following must never appear in any report field, diagnostic, fingerprint, or baseline entry:

| Forbidden | Reason |
|-----------|--------|
| Raw source code snippets | Privacy; volatility |
| Exception messages | Privacy; volatility |
| SQL payload values | Privacy; non-deterministic |
| User financial data | Privacy |
| Timestamps (in findings) | Volatility; not semantic identity |
| File system absolute paths | Platform-dependent |
| Stack traces | Privacy; non-deterministic |
| Arbitrary `e.message` | Unbounded; privacy |
| `..` path segments | Traversal risk |

Diagnostics use only **controlled reason/failure codes** from §4.2 and bounded `controlled_context` key-value pairs.

---

## 13. Integration expectations

### 13.1 Guard scripts

Every migrated guard must:

1. Emit a protocol-v2 JSON report when `COST_AGGREGATOR_GUARD_FINDINGS_FILE` is set.
2. Set `finding_protocol: 2` in the guard registry.
3. Support `--findings-output <path>` for explicit report path.
4. Honor `COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA=2`.
5. Continue emitting human-readable stdout for debugging.
6. Exit `0` for pass, `1` for violations (with `--fail-on-violation`), `2` for infrastructure error.

### 13.2 Ratchet

The ratchet must:

1. Create a unique temporary output file for each child execution.
2. Pass `COST_AGGREGATOR_GUARD_FINDINGS_FILE=<path>` and `COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA=2` to the child.
3. Load and validate the child's report.
4. Never parse stdout when `finding_protocol=2`.
5. Compare current findings against baseline using v2 fingerprints and counts.
6. Validate baseline schema version before comparison.
7. Exit `2` on any schema mismatch, malformed report, or child/report inconsistency.

### 13.3 Static suite

The static-suite runner must execute the same command and baseline as direct script invocation and the ratchet. Results must be identical across all execution paths.

### 13.4 Gradle integration

The Gradle task (`verifyDbAccessBoundaries` or equivalent) must produce the same findings, same baseline comparison, and same exit code as the Python scripts.

### 13.5 Execution contract (protocol v2)

| Child exit | Report state | Ratchet result |
|------------|--------------|----------------|
| 0 | valid, zero findings | continue (exit 0) |
| 0 | findings present | exit 2 (inconsistency) |
| 1 | valid, findings present | compare |
| 1 | zero/missing report | exit 2 |
| 2 | any | exit 2 |
| unexpected exit | any | exit 2 |
| malformed report | any | exit 2 |

---

## 14. Migration status

This protocol document is **pending**. The following work is not yet complete:

| Item | Status |
|------|--------|
| Protocol model (`guard_findings.py`) | NOT IMPLEMENTED |
| Rule catalog (`finding_rule_catalog.py`) | NOT IMPLEMENTED |
| Protocol tests (`test_guard_findings.py`) | NOT IMPLEMENTED |
| DB guard structured output | NOT IMPLEMENTED |
| Ratchet v2 consumption | NOT IMPLEMENTED |
| Guard registry v2 metadata | NOT IMPLEMENTED |
| Baseline migration | NOT IMPLEMENTED |
| Static-suite integration | NOT IMPLEMENTED |
| Gradle integration | NOT IMPLEMENTED |

**Do not claim this protocol is complete or active until all implementation, test, and review gates pass.**

The current migration remains in Phase 0 (freeze evidence). No baseline, policy, or structural-exceptions changes have been made.

---

## 15. Testing requirements

Protocol tests must cover:

| Test | Verifies |
|------|----------|
| Valid empty report | Envelope structure |
| Valid DB finding | Full model construction |
| Deterministic ordering | Sort stability |
| Windows path normalization | Cross-platform consistency |
| Path traversal rejected | Security |
| Unknown rule rejected | Catalog enforcement |
| Missing signature rejected | Identity completeness |
| Malformed JSON rejected | Robustness |
| Duplicate source occurrence rejected | Deduplication |
| Message changes do not affect fingerprint | Diagnostic separation |
| Line changes do not affect fingerprint | Location independence |
| Delimiter characters cannot cause collisions | Encoding safety |
| Parameter order changes fingerprint | Ordered identity |
| DAO operation changes fingerprint | Semantic identity |
| Count aggregation retains duplicates | Multiplicity |
| Source snippets cannot be serialized accidentally | Privacy enforcement |

---

## 16. References

| Document | Location |
|----------|----------|
| Discovery plan | `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` |
| V2 ledger | `docs/ci/GUARD_FINDING_DB_V2_LEDGER.md` |
| Guard framework | `docs/ci/guard-framework.md` |
| Guard policy | `docs/ci/guard-policy.md` |
| CI baseline inventory | `docs/ci/CI_GUARDRAILS_BASELINE.md` |
| Local CI guide | `docs/ci/local-ci.md` |

---

*This document describes the target protocol contract. Implementation is pending. No claims of final completion should be made until code, tests, and review gates pass on the `guard-finding-db-discovery-v2` branch.*
