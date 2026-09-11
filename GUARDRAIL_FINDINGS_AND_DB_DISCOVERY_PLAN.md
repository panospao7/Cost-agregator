# Guard Finding Protocol and DB Discovery Hardening Plan

**Starting SHA:** `bb2a6f18f12300af60ce1db475fb0c8d73f6b774`  
**Scope:** P0-1 through P0-5 only  
**Status target:** DB guard and ratchet become structurally truthful and independently green  
**Out of scope:** time-boundary cleanup, barrier dominance, worker/helper mediation, PSI migration, migration PR gate

---

# 1. Mission

Implement five connected corrections:

1. Replace text-derived DB fingerprints with a versioned structured finding contract.
2. Replace coarse rule-plus-file ratchet identities with symbol-level, multiplicity-aware identities.
3. Derive DAO mutators from Room declarations and SQL semantics instead of method-name prefixes.
4. Stop excluding complete files merely because their names end in `Dao.kt`.
5. Authorize exact overload signatures rather than unions of same-name methods.

Final invariant:

> Every supported Room mutation is discovered from its declaration, assigned to an exact caller signature, emitted as a structured finding, and compared through a versioned baseline without relying on diagnostic wording or line numbers.

---

# 2. Non-negotiable rules

Agents must not:

- regenerate `config/baselines/db_access.json` directly from the 385 findings;
- treat all 385 findings as legal;
- add more names to `MUTATION_VERBS` as the final mutator solution;
- add broad ownership-policy entries;
- preserve overload authorization by method-name union;
- skip a file based on its filename;
- baseline parser uncertainty or unsupported syntax;
- parse human-readable diagnostics to create v2 fingerprints;
- include line numbers or diagnostic messages in stable fingerprint identity;
- silently fall back to the legacy parser when v2 data is malformed;
- combine policy additions, application fixes, parser changes, and baseline migration in one commit.

Required exit codes:

```text
0 = pass
1 = architecture findings or ratchet growth/debt expiry
2 = parser, schema, configuration, protocol, or infrastructure failure
```

---

# 3. Target architecture

## 3.1 Structured guard report

Every migrated guard writes one JSON report:

```json
{
  "schema": "cost-aggregator.guard-findings",
  "schema_version": 2,
  "guard": "db_access",
  "findings": [],
  "diagnostics": [],
  "statistics": {}
}
```

Human-readable console output remains available, but the ratchet must never parse it in v2 mode.

## 3.2 Finding model

```json
{
  "rule": "DB_UNAUTHORIZED_MUTATION",
  "severity": "error",
  "path": "app/src/main/java/example/SomeRepository.kt",
  "location": {
    "line": 123,
    "column": 17
  },
  "symbol": {
    "owner": "com.example.SomeRepository",
    "name": "save",
    "receiver": null,
    "parameters": [
      "com.example.Expense"
    ],
    "kind": "function"
  },
  "identity": {
    "dao": "com.example.ExpenseDao",
    "accessor": "expenseDao",
    "operation": "insert",
    "mutation_kind": "ROOM_INSERT",
    "call_form": "qualified"
  },
  "message": "Mutation is not owned by an exact DB policy entry"
}
```

Rules:

- `path` is canonical repository-relative POSIX format.
- `location` is diagnostic only.
- `message` is diagnostic only.
- `symbol` and `identity` contain controlled values.
- Raw source lines, exception messages, SQL payload values, and user data are forbidden.
- Findings are sorted deterministically.

## 3.3 Stable fingerprint

The central ratchet, not each guard, constructs fingerprints.

Example:

```text
v2|db_access|DB_UNAUTHORIZED_MUTATION|path=app%2Fsrc%2F...|owner=com.example.SomeRepository|method=save|receiver=%3Cnone%3E|parameters=%5B%22com.example.Expense%22%5D|dao=com.example.ExpenseDao|operation=insert|kind=ROOM_INSERT
```

Values must use deterministic percent encoding.

The fingerprint excludes:

- line;
- column;
- message;
- timestamps;
- source snippets;
- report ordering.

## 3.4 Multiplicity

A fingerprint key also carries a count:

```json
{
  "fingerprint": "v2|...",
  "count": 2
}
```

This prevents two same-rule findings in the same method from collapsing into one.

Comparison rules:

```text
current count > baseline count = new findings
current count < baseline count = resolved findings
new key                       = new findings
missing current key           = resolved findings
same key and count            = unchanged
```

The ratchet should display occurrence locations, but locations do not define the baseline identity.

## 3.5 Versioned baseline

Target format:

```json
{
  "baseline_schema_version": 2,
  "guard_output_schema_version": 2,
  "fingerprint_schema_version": 2,
  "guard": "db_access",
  "generated_at": "ISO-8601",
  "entries": [
    {
      "fingerprint": "v2|...",
      "count": 1,
      "rule": "DB_UNAUTHORIZED_MUTATION",
      "classification": "temporary_debt",
      "reason": "Existing unsafe writer awaiting lifecycle migration",
      "owner": "@owner",
      "linked_issue": "MIT-...",
      "expires": "YYYY-MM-DD"
    }
  ]
}
```

Baseline entries represent only unresolved temporary debt.

Legal paths belong in ownership or structural policy, not in the baseline.

Schema mismatch must produce:

```text
RATCHET_BASELINE_SCHEMA_MISMATCH
```

and exit `2` before any new/resolved comparison.

---

# 4. Work and dependency graph

```text
Phase 0 — Freeze evidence

PR-F1 — Shared finding protocol
    ↓
PR-F2 — Ratchet v2 and count-aware comparison
    ↓
PR-D1 — Exact callable signature model
    ↓
PR-D2 — Room-derived mutator inventory
    ↓
PR-D3 — Declaration-level Dao.kt scanning
    ↓
PR-D4 — Structured DB finding output
    ↓
PR-D5 — Classify findings and migrate DB baseline
    ↓
PR-F3 — Migrate remaining ratcheted guards
    ↓
Final integration and adversarial verification
```

Do not create the final DB v2 baseline before PR-D1 through PR-D4 are complete. Otherwise the baseline would be generated from another incomplete scanner generation.

---

# 5. Phase 0 — Freeze current evidence

## 5.1 Branch setup

```bash
git fetch --all --prune
git checkout --detach bb2a6f18f12300af60ce1db475fb0c8d73f6b774
git checkout -b guard-finding-db-discovery-v2
git status --short
mkdir -p build/guard-v2/before
```

## 5.2 Capture current commands

```bash
python -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  scripts/ci/test_guard_ratchet.py \
  -v --tb=short \
  2>&1 | tee build/guard-v2/before/tests.log

python scripts/verify_db_access_boundaries.py \
  --fail-on-violation \
  2>&1 | tee build/guard-v2/before/db-guard.log

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

Expected current result is red. Preserve it.

## 5.3 Freeze source artifacts

Copy, do not modify:

```bash
cp config/baselines/db_access.json \
  build/guard-v2/before/db_access_v1.json

cp config/guards/db_ownership_policy.yml \
  build/guard-v2/before/db_ownership_policy.yml

cp config/guards/db_structural_exceptions.yml \
  build/guard-v2/before/db_structural_exceptions.yml
```

## 5.4 Ledger

Create:

```text
docs/ci/GUARD_FINDING_DB_V2_LEDGER.md
```

Record:

- starting SHA;
- current 15 baseline entries;
- current finding count;
- current scanner and baseline formats;
- commands and exit codes;
- no baseline change;
- no ownership-policy change.

Commit:

```text
chore(ci): freeze guard finding v2 migration evidence
```

---

# 6. PR-F1 — Shared finding protocol

## Objective

Create one typed, tested model used by guards, the ratchet, and the static-suite reporter.

## Files

```text
scripts/ci/guard_findings.py
scripts/ci/finding_rule_catalog.py
scripts/ci/test_guard_findings.py
docs/ci/GUARD_FINDING_PROTOCOL.md
```

## 6.1 Core models

Implement immutable dataclasses:

```text
SourceLocation
CallableSymbol
GuardFinding
GuardDiagnostic
GuardRunReport
FingerprintProfile
AggregatedFinding
```

### `SourceLocation`

Fields:

```text
line: positive integer
column: optional positive integer
end_line: optional positive integer
end_column: optional positive integer
```

### `CallableSymbol`

Fields:

```text
owner
name
receiver
parameters
kind
```

Allowed `kind` values:

```text
function
constructor
property_getter
property_setter
top_level_function
initializer
unknown
```

`unknown` may be used only for non-blocking discovery. A blocking finding requiring symbol identity must reject it with exit `2`.

### `GuardFinding`

Required fields:

```text
rule
severity
path
location
symbol
identity
message
```

### `GuardDiagnostic`

Used only for infrastructure/parser failures:

```text
code
path
symbol
controlled_context
```

Diagnostics are never baseline-able.

## 6.2 Rule catalog

Register each stable rule and its required identity fields.

Initial DB rules:

```text
DB_UNAUTHORIZED_MUTATION
DB_MISSING_WRITE_BARRIER
DB_FORBIDDEN_STRUCTURAL_OPERATION
```

Initial DB infrastructure codes:

```text
DB_SOURCE_UNREADABLE
DB_METHOD_BODY_UNSUPPORTED
DB_DAO_SCOPE_UNRESOLVED
DB_CALL_TARGET_AMBIGUOUS
DB_POLICY_SOURCE_EVIDENCE_INVALID
DB_ROOM_QUERY_UNCLASSIFIABLE
DB_SIGNATURE_UNRESOLVED
DB_DAO_INHERITANCE_UNRESOLVED
```

Example profile:

```python
"DB_UNAUTHORIZED_MUTATION": FingerprintProfile(
    guard="db_access",
    identity_fields=(
        "path",
        "symbol.owner",
        "symbol.name",
        "symbol.receiver",
        "symbol.parameters",
        "identity.dao",
        "identity.operation",
        "identity.mutation_kind",
    ),
    multiplicity="count",
)
```

Unknown rule IDs are infrastructure failures.

## 6.3 Validation

Reject:

- absolute paths;
- backslashes;
- `..`;
- missing source root;
- blank rule/path/symbol values;
- unknown rule;
- identity values of unsupported type;
- line zero or negative;
- duplicate exact source occurrences;
- raw exception fields;
- unbounded strings;
- finding guard inconsistent with rule catalog.

Recommended limits:

```text
path: 500 characters
symbol component: 300
identity scalar: 300
message: 500
findings per report: 100,000
```

## 6.4 Serialization

Implement:

```python
load_report(path)
write_report_atomic(path, report)
validate_report(report)
canonicalize_report(report)
```

Writing must use:

1. temporary sibling file;
2. flush;
3. atomic rename.

## 6.5 Tests

Required tests:

- valid empty report;
- valid DB finding;
- deterministic ordering;
- Windows path normalization;
- path traversal rejected;
- unknown rule rejected;
- missing signature rejected where required;
- malformed JSON rejected;
- duplicate source occurrence rejected;
- message changes do not affect fingerprint;
- line changes do not affect fingerprint;
- delimiter characters cannot cause collisions;
- parameter order changes fingerprint;
- DAO operation changes fingerprint;
- count aggregation retains duplicate occurrences;
- source snippets cannot be serialized accidentally.

## Acceptance

```bash
python -m pytest scripts/ci/test_guard_findings.py -v --tb=short
```

Commit:

```text
feat(ci): define versioned guard finding protocol
```

---

# 7. PR-F2 — Ratchet v2

## Objective

Make the ratchet consume structured reports and compare semantic counts.

## Files

```text
scripts/ci/guard_ratchet.py
scripts/ci/test_guard_ratchet.py
scripts/ci/test_guard_ratchet_v2.py
scripts/ci/guard_registry.py
docs/ci/GUARD_FINDING_PROTOCOL.md
```

## 7.1 Child-output transport

The ratchet creates a unique temporary output file and starts the child with:

```text
COST_AGGREGATOR_GUARD_FINDINGS_FILE=<temporary-path>
COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA=2
```

The shared reporter checks that environment variable.

Benefits:

- no shell argument construction;
- no stale checked-in report;
- no collision between parallel jobs;
- no need to parse stdout.

## 7.2 Registry metadata

Add fields:

```python
"finding_protocol": 2,
"fingerprint_schema": 2,
```

During migration only:

```python
"finding_protocol": 1,
"legacy_parser_expiry": "YYYY-MM-DD",
```

A v1 guard without expiry fails registry validation.

## 7.3 Execution contract

For protocol v2:

| Child state | Report state | Ratchet result |
|---|---|---|
| exit 0 | valid, zero findings | continue |
| exit 0 | findings present | exit 2 |
| exit 1 | valid, findings present | compare |
| exit 1 | zero/missing report | exit 2 |
| exit 2 | any | exit 2 |
| unexpected exit | any | exit 2 |
| malformed report | any | exit 2 |

Do not parse stdout if `finding_protocol=2`.

## 7.4 Baseline validation

Validate before comparison:

```text
baseline_schema_version == 2
guard_output_schema_version == report.schema_version
fingerprint_schema_version == registry fingerprint schema
guard == requested guard
entries is a list
fingerprints unique
counts positive
classification == temporary_debt
owner/reason/issue/expiry present
expiry valid
```

Outcomes:

```text
schema/configuration invalid = exit 2
expired debt              = exit 1
new count/key             = exit 1
resolved count/key        = exit 1 until reviewed shrink
unchanged                 = exit 0
```

## 7.5 Comparison algorithm

Create current grouped counts:

```python
current = {
    fingerprint: {
        "count": N,
        "locations": [...]
    }
}
```

Compare with baseline counts.

Report:

```text
NEW_KEYS
NEW_OCCURRENCES
RESOLVED_KEYS
RESOLVED_OCCURRENCES
UNCHANGED_KEYS
EXPIRED_BASELINE_ENTRIES
```

## 7.6 Baseline maintenance

Do not allow v1-to-v2 conversion through `--update-baseline`.

Add:

```text
--propose-baseline <path>
```

This writes a candidate file but never overwrites the active baseline and never returns success when unresolved classifications exist.

Retain `--update-baseline` only for reviewed v2 debt reductions:

- schema already v2;
- no count increases;
- no new keys;
- explicit local mode;
- prohibited under `--ci-mode`.

## 7.7 Tests

Required tests:

- v1 baseline with v2 guard produces `RATCHET_BASELINE_SCHEMA_MISMATCH`, exit 2;
- diagnostic wording changes remain unchanged;
- source line moves remain unchanged;
- second same-rule occurrence increases count;
- removing one occurrence reports one resolved occurrence;
- guard mismatch exits 2;
- unknown rule exits 2;
- malformed report exits 2;
- child exit/report inconsistency exits 2;
- duplicate baseline keys exit 2;
- expired debt exits 1;
- `--update-baseline` cannot perform schema migration;
- candidate generation never edits active baseline;
- Windows and POSIX produce identical fingerprints.

## Acceptance

```bash
python -m pytest \
  scripts/ci/test_guard_findings.py \
  scripts/ci/test_guard_ratchet.py \
  scripts/ci/test_guard_ratchet_v2.py \
  -v --tb=short
```

Commit:

```text
feat(ci): add structured count-aware guard ratchet v2
```

---

# 8. PR-D1 — Exact callable signatures

## Objective

Identify each caller overload independently before changing mutation discovery.

## Files

Recommended extraction:

```text
scripts/db_guard/__init__.py
scripts/db_guard/models.py
scripts/db_guard/kotlin_symbols.py
scripts/db_guard/type_resolution.py
scripts/test_db_guard_symbols.py
scripts/migrate_db_policy_signatures.py
config/guards/db_ownership_policy.yml
```

## 8.1 Symbol identity

Define:

```text
CallableId
- canonical_path
- owner_fqcn
- name
- extension_receiver
- parameter_types
```

Example:

```text
app/src/main/.../Repository.kt
com.example.Repository
save
receiver = null
parameters = [com.example.Expense]
```

Do not use:

- return type;
- parameter names;
- visibility;
- annotations;
- `suspend`;
- default values.

Include:

- parameter order;
- qualified type;
- generic arguments;
- nullability;
- `vararg`;
- extension receiver.

## 8.2 Type normalization

Resolve using:

1. Kotlin built-ins.
2. Explicit imports.
3. Import aliases.
4. Same-package declarations.
5. Nested declarations.
6. Generic type parameters and bounds.

Fail with `DB_SIGNATURE_UNRESOLVED` for:

- ambiguous star imports;
- unresolved type aliases;
- unsupported function-type syntax;
- incomplete generic syntax;
- conflicting aliases.

Do not fall back to a simple name when resolution is ambiguous.

## 8.3 Policy schema v2

Target:

```yaml
version: 2
entries:
  - path: app/src/main/java/.../Repository.kt
    class: Repository
    method: save
    signature:
      receiver: null
      parameters:
        - com.example.Expense
    daos:
      - expenseDao
    operation: insert
```

Signatures should eventually be mandatory for every entry, not only overloaded methods. This prevents a future overload from silently inheriting existing authorization.

## 8.4 Policy migration tool

Commands:

```bash
python scripts/migrate_db_policy_signatures.py --check
python scripts/migrate_db_policy_signatures.py \
  --write-candidate build/guard-v2/db_ownership_policy_v2.yml
```

The tool must:

- never overwrite the active policy directly;
- resolve each entry to source;
- identify exact candidate signatures;
- use DAO/operation evidence only to disambiguate;
- refuse ambiguous matches;
- preserve reason, owner and linked issue;
- produce deterministic YAML;
- emit a migration report.

Report:

```text
build/guard-v2/policy-signature-migration.json
```

Statuses:

```text
RESOLVED_EXACTLY
AMBIGUOUS_OVERLOAD
METHOD_MISSING
SIGNATURE_UNSUPPORTED
PAIR_NOT_FOUND
```

## 8.5 Scanner changes

Replace policy grouping:

```text
path + class + method
```

with:

```text
path + class + method + receiver + parameter types
```

Delete overload-union behavior.

Every overload gets:

- its own body;
- its own DAO map;
- its own mutation set;
- its own barrier evidence;
- its own policy entries.

## 8.6 Required tests

```kotlin
suspend fun save(expense: Expense) {
    expenseDao.insert(expense)
}

suspend fun save(receipt: Receipt) {
    receiptDao.insert(receipt)
}
```

Test:

1. Expense authorization does not authorize Receipt.
2. Receipt authorization does not authorize Expense.
3. Method-only policy is rejected.
4. Same name and arity with different types remain distinct.
5. Nullable and non-null parameters remain distinct.
6. Generic signatures normalize deterministically.
7. Extension receiver participates in identity.
8. Default values do not affect identity.
9. Import aliases resolve correctly.
10. Ambiguous type resolution exits 2.
11. Adding a new overload after approval requires new policy.

## Acceptance

```bash
python -m pytest \
  scripts/test_db_guard_symbols.py \
  scripts/test_verify_db_access_boundaries.py \
  -v --tb=short

python scripts/migrate_db_policy_signatures.py --check
```

Commit:

```text
feat(ci): authorize DB writers by exact callable signature
```

---

# 9. PR-D2 — Room-derived mutator inventory

## Objective

Determine mutations from Room declarations, not method names.

## Files

```text
scripts/db_guard/room_inventory.py
scripts/db_guard/sql_classifier.py
scripts/db_guard/dao_accessors.py
scripts/test_db_guard_room_inventory.py
scripts/test_db_guard_sql_classifier.py
config/guards/db_raw_query_classification.yml
```

## 9.1 Data model

```text
DaoId
- fqcn
- path

DaoMethodId
- dao
- name
- receiver
- parameters

RoomMutator
- method
- mutation_kind
- annotation
- query_kind
- inherited_from
- source_location
```

Mutation kinds:

```text
ROOM_INSERT
ROOM_UPDATE
ROOM_DELETE
ROOM_UPSERT
ROOM_MUTATING_QUERY
ROOM_RAW_WRITE
ROOM_TRANSITIVE_WRAPPER
```

## 9.2 Discover DAOs

Discover declarations by `@Dao`, including qualified annotation names.

Support:

- interfaces;
- abstract classes;
- multiple declarations in one file;
- DAO in a file not ending in `Dao.kt`;
- multiline annotation;
- nested declaration where resolvable;
- generic base DAO.

Filename and class suffix are not evidence.

## 9.3 Direct Room annotations

Always mutating:

```text
@Insert
@Update
@Delete
@Upsert
```

The declared method name becomes the exact operation.

Therefore all must be detected:

```text
save
persist
remove
wipe
applyStatus
store
put
create
```

No mutation verb list is consulted.

## 9.4 `@Query` classification

Extract constant SQL and tokenize it.

Mutating top-level statements:

```text
INSERT
UPDATE
DELETE
REPLACE
CREATE
DROP
ALTER
VACUUM
ATTACH
DETACH
```

Read statements:

```text
SELECT
```

Support:

```text
WITH ... SELECT
WITH ... INSERT
WITH ... UPDATE
WITH ... DELETE
WITH RECURSIVE ...
```

The SQL tokenizer must track:

- parenthesis depth;
- single/double/backtick quotes;
- SQL comments;
- top-level tokens;
- CTE bodies.

Do not use substring matching.

Uncertain statements produce:

```text
DB_ROOM_QUERY_UNCLASSIFIABLE
```

and exit `2`.

## 9.5 `@RawQuery`

Add exact classification:

```yaml
version: 1
methods:
  - dao: com.example.RepairDao
    method: executeRepair
    signature:
      receiver: null
      parameters:
        - androidx.sqlite.db.SupportSQLiteQuery
    classification: write
    reason: "Controlled repair query"
    owner: "@owner"
    linked_issue: "MIT-..."
```

Unclassified `@RawQuery` is an infrastructure failure.

Reject stale entries.

## 9.6 Inheritance

Build a DAO inheritance graph.

For:

```kotlin
interface ExpenseDao : BaseDao<Expense>
```

inherit all mutators from `BaseDao`.

Requirements:

- fixed-point traversal;
- generic substitution where needed;
- cycle detection;
- unresolved parent failure;
- ambiguous parent failure;
- source roots from all production modules containing Room declarations.

## 9.7 DAO default wrappers

Example:

```kotlin
@Transaction
suspend fun replace(item: Item) {
    remove(item)
    save(item)
}
```

Perform fixed-point classification:

1. Seed annotated mutators.
2. Inspect same-DAO method calls in default bodies.
3. Mark wrappers calling a mutator as `ROOM_TRANSITIVE_WRAPPER`.
4. Repeat until stable.

Unknown function references or dynamic dispatch fail closed.

## 9.8 Database accessor inventory

Discover accessors such as:

```kotlin
abstract fun expenseDao(): ExpenseDao
```

Create:

```text
expenseDao -> com.example.ExpenseDao
```

Fail on:

- duplicate accessor;
- unknown return DAO;
- ambiguous accessor;
- malformed declaration.

## 9.9 Replace mutation grammar

Call-site detection becomes:

1. Resolve receiver/accessor to DAO type.
2. Resolve called method name and supported arity/signature.
3. Look up the method in the Room inventory.
4. Classify as mutation only when inventory says it mutates.
5. Emit exact mutation kind.

Keep `MUTATION_VERBS` temporarily only in comparison mode:

```text
ROOM_ONLY
VERB_ONLY
BOTH
```

Every `VERB_ONLY` result must be investigated.

Remove authoritative use of `MUTATION_VERBS` before final integration.

## Required tests

- `@Insert fun save`;
- `@Upsert fun persist`;
- `@Delete fun remove`;
- mutating `@Query fun wipe`;
- mutating `@Query fun applyStatus`;
- read method with mutation-like name is not a mutation;
- read CTE;
- mutating CTE;
- qualified annotations;
- inherited generic mutator;
- inheritance cycle;
- missing parent;
- raw query read/write classifications;
- missing raw-query classification;
- stale raw-query classification;
- transitive wrapper;
- overloaded DAO method ambiguity;
- accessor in `AppDatabase`;
- DAO in unexpected filename.

## Artifact

Generate:

```text
build/reports/db-guard/room-mutator-inventory.json
```

Include counts, declarations and controlled source locations.

## Acceptance

```bash
python -m pytest \
  scripts/test_db_guard_room_inventory.py \
  scripts/test_db_guard_sql_classifier.py \
  -v --tb=short

python scripts/verify_db_access_boundaries.py \
  --dump-room-mutators build/reports/db-guard/room-mutator-inventory.json
```

Commit:

```text
feat(ci): derive DB mutations from Room declarations
```

---

# 10. PR-D3 — Declaration-level scanning of `Dao.kt` files

## Objective

Scan every Kotlin file while excluding only actual DAO declaration operations from caller analysis.

## Implementation

Remove:

```python
if filename.endswith("Dao.kt"):
    continue
```

For every Kotlin file:

1. Parse all declarations.
2. Obtain exact ranges for declarations identified as `@Dao`.
3. Treat abstract DAO methods as declarations, not call sites.
4. Feed default DAO method bodies to Room wrapper analysis.
5. Continue ordinary caller scanning for:
   - top-level functions;
   - helper classes;
   - helper objects;
   - companion objects;
   - extension functions;
   - non-DAO interfaces;
   - property accessors;
   - initializers.
6. Count the file as scanned once.

## Required fixtures

### Helper inside DAO file

```kotlin
@Dao
interface ExpenseDao {
    @Insert
    suspend fun save(expense: Expense)
}

class UnsafeWriter(
    private val expenseDao: ExpenseDao
) {
    suspend fun write(expense: Expense) {
        expenseDao.save(expense)
    }
}
```

Expected: unauthorized mutation.

### Top-level writer

```kotlin
suspend fun unsafeWrite(
    dao: ExpenseDao,
    expense: Expense
) {
    dao.save(expense)
}
```

Expected: unauthorized or controlled unsupported symbol error, never skipped.

### Companion writer

Expected: scanned under exact companion symbol.

### Misleading filename

A non-DAO file named `ReportingDao.kt` must be scanned.

### DAO elsewhere

An `@Dao` declaration in `StorageContracts.kt` must be inventoried.

### Multiple declarations

A DAO plus two helper classes in one file must scan both helpers.

## Acceptance

```bash
python -m pytest \
  scripts/test_verify_db_access_boundaries.py \
  -k "dao_file or dao_declaration" \
  -v
```

Generate:

```text
build/guard-v2/dao-file-scan-delta.json
```

Commit:

```text
fix(ci): replace Dao filename exclusion with declaration analysis
```

---

# 11. PR-D4 — Structured DB findings

## Objective

Make `verify_db_access_boundaries.py` emit protocol-v2 reports without parsing its own messages.

## Refactor

Recommended files:

```text
scripts/db_guard/scanner.py
scripts/db_guard/reporting.py
scripts/db_guard/rules.py
scripts/verify_db_access_boundaries.py
```

The top-level script should become CLI orchestration.

Replace tuple findings such as:

```text
(path, line, source, reason-text)
```

with typed `GuardFinding`.

## Policy findings

Baseline-able:

```text
DB_UNAUTHORIZED_MUTATION
DB_MISSING_WRITE_BARRIER
DB_FORBIDDEN_STRUCTURAL_OPERATION
```

## Infrastructure diagnostics

Never baseline-able:

```text
DB_SOURCE_UNREADABLE
DB_METHOD_BODY_UNSUPPORTED
DB_EXPRESSION_BODY_UNSUPPORTED
DB_DAO_SCOPE_UNRESOLVED
DB_CALL_TARGET_AMBIGUOUS
DB_SIGNATURE_UNRESOLVED
DB_ROOM_QUERY_UNCLASSIFIABLE
DB_POLICY_SOURCE_EVIDENCE_INVALID
```

If any infrastructure diagnostic exists:

- write controlled diagnostics to the report;
- exit `2`;
- do not emit partial authorization success.

## CLI

Support:

```text
--findings-output <path>
--dump-room-mutators <path>
--inventory-only
--fail-on-violation
```

Also honor:

```text
COST_AGGREGATOR_GUARD_FINDINGS_FILE
```

## Human output

Example:

```text
DB_UNAUTHORIZED_MUTATION app/src/.../Repository.kt:123
  symbol: com.example.Repository#save(com.example.Expense)
  dao: com.example.ExpenseDao
  operation: insert
```

The message may change without affecting the fingerprint.

## Tests

- wording change produces identical fingerprint;
- line shift produces identical fingerprint;
- overload change produces different fingerprint;
- second identical operation increments count;
- operation change produces different fingerprint;
- parser failure exits 2 and is not baseline-able;
- report path is written atomically;
- zero findings and exit 0 are consistent;
- findings and exit 1 are consistent;
- report contains no raw source text.

## Acceptance

```bash
python scripts/verify_db_access_boundaries.py \
  --findings-output build/guard-v2/db-current.json \
  --fail-on-violation

python scripts/ci/guard_ratchet.py \
  --guard-name db_access \
  --command-arg=python \
  --command-arg=scripts/verify_db_access_boundaries.py \
  --command-arg=--fail-on-violation \
  --baseline config/baselines/db_access.json \
  --fail-on-violation \
  --ci-mode
```

At this stage, the second command should exit `2` with the explicit baseline-schema mismatch. It must not print “385 new / 15 resolved.”

Commit:

```text
feat(ci): emit structured DB architecture findings
```

---

# 12. PR-D5 — Classify findings and migrate the DB baseline

## Objective

Reconcile the old 15 coarse entries and the final v2 finding inventory without normalizing unknown violations.

## Files

```text
scripts/ci/build_db_finding_triage.py
scripts/ci/generate_db_baseline_v2.py
scripts/ci/test_db_finding_triage.py
docs/ci/DB_ACCESS_V2_TRIAGE.yml
config/baselines/db_access.json
```

## 12.1 Generate final inventory

Run the final scanner, not the old scanner:

```bash
python scripts/verify_db_access_boundaries.py \
  --inventory-only \
  --findings-output build/guard-v2/db-final-inventory.json
```

The resulting count may differ from 385. Use the new count as the actual triage input.

## 12.2 Crosswalk old baseline

For every old v1 entry:

1. Extract old rule and path.
2. Find all final v2 findings with matching semantic rule family and path.
3. Produce one of:
   - `ONE_TO_ONE`;
   - `ONE_TO_MANY`;
   - `NO_CURRENT_MATCH`;
   - `UNRESOLVED_RULE_MAPPING`.

No mapping is automatically accepted.

## 12.3 Triage schema

```yaml
- fingerprint: "v2|..."
  classification: PENDING
  path: ...
  symbol: ...
  dao: ...
  operation: ...
  present_at_reference_sha: unknown
  owner: null
  linked_issue: null
  reason: null
  expires: null
  evidence: []
```

Allowed final classifications:

```text
LEGAL_WRITER_POLICY_MISSING
REAL_ARCHITECTURE_VIOLATION
PARSER_FALSE_POSITIVE
PREEXISTING_TEMPORARY_DEBT
STRUCTURAL_OPERATION
ANALYZER_UNSUPPORTED
DUPLICATE_DETECTION
```

## 12.4 Classification rules

### `LEGAL_WRITER_POLICY_MISSING`

Required action:

- inspect lifecycle ownership;
- inspect barrier behavior;
- add exact signature policy;
- add policy-source evidence test;
- remove from finding set.

Never baseline it.

### `REAL_ARCHITECTURE_VIOLATION`

Required action:

- route through legal owner;
- add regression fixture;
- remove from finding set.

Never baseline it.

### `PARSER_FALSE_POSITIVE`

Required action:

- fix analyzer;
- positive and negative parser fixture;
- remove from finding set.

Never baseline it.

### `PREEXISTING_TEMPORARY_DEBT`

Baseline only when:

- proven present at approved reference SHA;
- owner assigned;
- issue linked;
- reason provided;
- expiry provided;
- reviewer approves;
- no safer immediate fix exists.

### `STRUCTURAL_OPERATION`

Required action:

- approve through exact structural policy if legal;
- otherwise remove/fix operation.

Do not baseline if structural policy can represent it.

### `ANALYZER_UNSUPPORTED`

Required action:

- analyzer exits `2`;
- parser support must be added.

Never baseline it.

### `DUPLICATE_DETECTION`

Required action:

- deduplicate by exact source range;
- add regression test.

Never baseline it.

## 12.5 Historical proof

Use a temporary worktree and the final inventory engine:

```bash
git worktree add build/worktrees/db-reference <APPROVED_REFERENCE_SHA>
```

Run discovery-only mode against both source trees.

The orchestrator must explicitly choose and record the reference SHA. Do not let the agent infer one silently.

## 12.6 Batched review

Process no more than 25 findings per agent batch.

Each batch reports:

```text
finding count
classifications
policy additions
code fixes
parser fixes
remaining pending
baseline candidates
```

An independent reviewer validates all `LEGAL_WRITER_POLICY_MISSING` and `PREEXISTING_TEMPORARY_DEBT` classifications.

## 12.7 Generate baseline candidate

The generator accepts only triage entries classified:

```text
PREEXISTING_TEMPORARY_DEBT
```

It rejects:

- pending entries;
- missing metadata;
- expired entries;
- unsupported findings;
- legal writers;
- parser false positives;
- unproven historical presence.

Command:

```bash
python scripts/ci/generate_db_baseline_v2.py \
  --triage docs/ci/DB_ACCESS_V2_TRIAGE.yml \
  --output build/guard-v2/db_access_v2_candidate.json
```

Review:

```bash
git diff --no-index \
  config/baselines/db_access.json \
  build/guard-v2/db_access_v2_candidate.json
```

Only after review copy the candidate into the active baseline.

## 12.8 Acceptance

```bash
python scripts/ci/guard_ratchet.py \
  --guard-name db_access \
  --command-arg=python \
  --command-arg=scripts/verify_db_access_boundaries.py \
  --command-arg=--fail-on-violation \
  --baseline config/baselines/db_access.json \
  --fail-on-violation \
  --ci-mode
```

Expected:

```text
exit 0
schema mismatch: 0
new keys/counts: 0
resolved keys/counts: 0
pending triage: 0
expired debt: 0
infrastructure diagnostics: 0
```

Commits should remain separate:

```text
fix(db): resolve unsafe DB writer batch N
fix(ci): correct DB scanner false positives batch N
chore(ci): register exact legal DB writers batch N
chore(ci): migrate reviewed DB debt baseline to v2
```

---

# 13. PR-F3 — Migrate other ratcheted guards

## Objective

Finish P0-2 globally so the ratchet no longer uses coarse text extraction.

Migrate in this order:

```text
cancellation
event_writers
money
privacy
migration_matrix
```

For each guard:

1. Define stable rule IDs.
2. Define exact identity profile.
3. Add structured report output.
4. Add multiplicity tests.
5. Crosswalk the current baseline.
6. Classify ambiguous old entries.
7. Create reviewed v2 baseline.
8. Set `finding_protocol: 2` in registry.
9. Remove that guard’s legacy stdout parser support.

Recommended identities:

### Cancellation

```text
path
owner
method signature
pattern subtype
caught type
```

### Event writers

```text
path
owner
method signature
writer/event DAO
operation
event family
```

### Money

```text
path
owner
method signature
rule subtype
aggregation/expression category
```

### Privacy

```text
path
owner
method signature
rule subtype
source/sink category
```

### Migration matrix

```text
rule subtype
from version
to version
schema or migration identifier
```

After all migrations, remove:

```text
extract_fingerprints()
_PATH_LINE_RE
_BRACKET_RULE_RE
_STANDARD_RE
_MONEY_RULE_RE
```

from the ratchet.

Commit per guard:

```text
feat(ci): migrate <guard> ratchet findings to protocol v2
```

---

# 14. Static-suite and Gradle integration

Update the guard registry to carry canonical execution metadata:

```python
"db_access": {
    ...
    "finding_protocol": 2,
    "fingerprint_schema": 2
}
```

The static suite and Gradle task must execute the same command and baseline.

Required checks:

```bash
python scripts/ci/verify_guard_registry.py

python scripts/ci/run_static_guard_suite.py

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace
```

The DB result must be identical in:

- direct script execution;
- ratchet execution;
- static suite;
- Gradle task.

Compare:

```text
guard
schema versions
current key count
current occurrence count
baseline key count
baseline occurrence count
new/resolved/unchanged counts
```

---

# 15. Adversarial acceptance suite

Inject one violation at a time in a temporary worktree.

## Fingerprint tests

1. Add blank lines above a violation: unchanged.
2. Change human message: unchanged.
3. Add second violation in same file and method: count increases.
4. Move violation to another overload: new fingerprint.
5. Change operation `insert` to `delete`: new fingerprint.
6. Change DAO: new fingerprint.
7. Supply v1 baseline to v2 guard: exit 2.
8. Corrupt findings JSON: exit 2.
9. Make child exit 1 without report: exit 2.

## Room discovery tests

1. Add `@Insert fun save`.
2. Add `@Upsert fun persist`.
3. Add `@Delete fun remove`.
4. Add `@Query("DELETE ...") fun wipe`.
5. Add `@Query("UPDATE ...") fun applyStatus`.
6. Add harmless read method named `updatePreview`.
7. Add inherited base-DAO mutator.
8. Add unclassified raw query.

Unauthorized calls to cases 1–5 and 7 must fail without editing a verb list.

## `Dao.kt` tests

1. Helper writer inside `ExpenseDao.kt`.
2. Top-level writer inside `ExpenseDao.kt`.
3. Companion writer inside `ExpenseDao.kt`.
4. DAO declaration inside `StorageContracts.kt`.

All caller mutations must be analyzed.

## Overload tests

1. Approve `save(Expense)`.
2. Call `save(Receipt)` from unauthorized path.
3. Add a third overload.
4. Swap DAO operations between overload bodies.
5. Remove signature from policy.

Each must fail appropriately.

Revert every artificial mutation.

---

# 16. Agent assignments

## Agent A — Finding protocol

Owns PR-F1 only.

Must not modify:

```text
verify_db_access_boundaries.py
db ownership policy
db baseline
```

## Agent B — Ratchet v2

Owns PR-F2 after Agent A.

Must not migrate any active baseline.

## Agent C — Callable signatures

Owns PR-D1.

Deliverables:

```text
signature inventory
policy candidate
ambiguity report
tests
```

## Agent D — Room inventory

Owns PR-D2 after callable identity is stable.

Must not add mutation verbs.

## Agent E — DAO declaration scanning

Owns PR-D3 after Room declaration inventory exists.

## Agent F — DB reporting

Owns PR-D4 after signatures and Room inventory are integrated.

## Agent G — DB triage coordinator

Owns PR-D5.

May dispatch domain-specific batches but cannot approve its own baseline classifications.

## Agent H — Other guard migration

Owns PR-F3 after DB protocol is proven.

## Agent I — Independent adversarial reviewer

Must not edit the primary implementation.

Attempts:

```text
fingerprint collisions
same-file duplicate violations
unusual Room method names
Dao.kt helper bypass
overload-policy confusion
malformed report/baseline handling
```

---

# 17. Agent reporting format

After every assignment:

```text
PHASE:
START SHA:
END SHA:
FILES CHANGED:
TESTS ADDED:
COMMANDS RUN:
EXIT CODES:
CURRENT FINDING KEYS:
CURRENT FINDING OCCURRENCES:
POLICY CHANGES:
BASELINE CHANGES:
NEWLY DISCOVERED WRITES:
UNSUPPORTED SYNTAX:
FALSE POSITIVES:
REAL VIOLATIONS:
REMAINING PENDING TRIAGE:
NEXT SAFE STEP:
```

Every claim of completion must reference:

- exact commit;
- exact command;
- exit code;
- test count;
- generated report path.

---

# 18. Merge order

```text
1. Phase 0 evidence
2. PR-F1 finding protocol
3. PR-F2 ratchet v2
4. PR-D1 callable signatures
5. PR-D2 Room mutator inventory
6. PR-D3 declaration-level Dao.kt scan
7. PR-D4 DB structured reporting
8. DB finding remediation batches
9. PR-D5 reviewed DB baseline v2
10. PR-F3 remaining ratcheted guards
11. Static-suite/Gradle integration
12. Independent adversarial review
```

Do not merge the final DB baseline before the exact-signature, Room-inventory, and `Dao.kt` changes are active.

---

# 19. Definition of done for these five issues

These issues are complete only when:

- ratchet fingerprints come exclusively from structured reports;
- baseline and finding schemas are versioned;
- schema mismatch exits `2`;
- human wording changes do not alter fingerprints;
- line movement does not alter fingerprints;
- a second same-rule occurrence increases the ratchet count;
- all ratcheted guards have symbol-aware identities;
- Room annotations and query semantics determine DAO mutators;
- unusual names such as `save`, `persist`, `remove`, `wipe`, and `applyStatus` are detected;
- no production Kotlin file is excluded by filename;
- non-DAO declarations inside `*Dao.kt` files are scanned;
- each overloaded caller has a separate signature identity;
- policy entries cannot authorize unions of overloads;
- all legacy 15 DB entries have a documented v2 disposition;
- all final DB findings are classified;
- unsupported analysis states exit `2` and are never baselined;
- the v2 DB baseline contains only reviewed temporary debt;
- direct, ratchet, static-suite, and Gradle DB checks produce identical results;
- all adversarial fixtures fail as intended;
- the DB guard-specific gates are green on one exact final SHA.

This does not mark the entire application guardrail project complete. Time boundaries, control-flow-safe barriers, worker/helper mediation, PSI migration, migration PR gating, and overlapping legacy guards remain separate workstreams.