# DB Room Mutator Inventory — Trust and Diagnostic Contract

**Status: PARTIAL / PENDING REVIEW**
**Plan:** `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` (PR-D2)
**Branch:** `guard-finding-db-discovery-v2`
**Last updated:** 2026-08-14

This document defines the **D2 inventory trust contract**: the boundary
between a `DB_ROOM_` inventory run and any downstream consumer (ratchet,
baseline, policy decision, triage). It records the D2 scanner contract as
implemented in the isolated worktree
`build/worktrees/guard-finding-db-discovery-v2`. It makes **no completion
claim**; D2 remains PARTIAL / PENDING REVIEW.

---

## 1. Purpose

This document establishes the rule that **infrastructure diagnostics in an
inventory report render the entire inventory untrusted for authorization**.
It defines which consumers may use inventory output, under what conditions,
and what must happen when the inventory cannot be trusted.

---

## 2. Scope

Applies to:

- `scripts/db_guard/room_inventory.py` (Room mutator inventory)
- `scripts/verify_db_access_boundaries.py` when operating in
  `--inventory-only` or `--dump-room-mutators` mode
- `scripts/ci/guard_ratchet.py` when consuming inventory output for DB
  guard authorization
- Any downstream consumer of
  `build/reports/db-guard/room-mutator-inventory.json`

---

## 3. Infrastructure diagnostic codes

The following codes are **DB_ROOM_** infrastructure diagnostics. They
indicate the scanner could not fully resolve the Room inventory for a
declaration, DAO, or query.

| Code | Meaning |
|------|---------|
| `DB_ROOM_QUERY_UNCLASSIFIABLE` | A `@Query` SQL statement could not be tokenized or classified as read/write |
| `DB_ROOM_INVALID_SOURCE` | Non-directory or non-approved source root |
| `DB_ROOM_SOURCE_UNREADABLE` | Source file or directory could not be read |
| `DB_ROOM_SOURCE_EMPTY` | No Kotlin sources or no DAOs under the approved root |
| `DB_ROOM_INVALID_INPUT` | Room DAO accessor input is invalid |
| `DB_ROOM_BAD_PATH` | Room DAO source path is invalid |
| `DB_ROOM_UNSUPPORTED_DECLARATION` | Room DAO declaration syntax is unsupported |
| `DB_ROOM_AMBIGUOUS_DECLARATION` | Room DAO declaration is ambiguous |
| `DB_ROOM_MISSING_DECLARATION` | Room DAO declaration cannot be found |
| `DB_ROOM_UNSUPPORTED_METHOD` | Unsupported method form |
| `DB_ROOM_AMBIGUOUS_METHOD` | Room DAO method identity is ambiguous |
| `DB_ROOM_DUPLICATE_METHOD` | Duplicate method identity within a DAO |
| `DB_ROOM_MUTATOR_IDENTITY_AMBIGUOUS` | Mutator identity resolution is ambiguous |
| `DB_ROOM_ANNOTATION_CONFLICT` | Conflicting Room annotations on the same method |
| `DB_ROOM_INHERITED_METHOD_CONFLICT` | Inherited method conflicts with a direct declaration |
| `DB_ROOM_DATABASE_VERSION_CONFLICT` | Database version mismatch |
| `DB_ROOM_RAW_QUERY_POLICY_REQUIRED` | A discovered `@RawQuery` has no matching canonical policy entry |
| `DB_ROOM_RAW_QUERY_POLICY_STALE` | A policy entry has no discovered counterpart in source |
| `DB_ROOM_RAW_QUERY_POLICY_INVALID` | A policy entry is malformed, wildcarded, or duplicate |
| `DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS` | An inherited `@RawQuery` is exposed by multiple parents |
| `DB_DAO_INHERITANCE_UNRESOLVED` | DAO inheritance graph has a cycle, missing parent, or ambiguous parent |
| `DB_DAO_INHERITANCE_INVALID_ANCESTOR` | DAO inheritance references an invalid ancestor |
| `DB_DAO_ANNOTATION_SCOPE_UNRESOLVED` | Legal `@Dao` annotation-to-declaration span exceeds the documented safe maximum |
| `DB_SIGNATURE_UNRESOLVED` | Exact callable signature could not be determined (overload ambiguity) |
| `DB_CALL_TARGET_AMBIGUOUS` | Call-site target resolution failed (receiver or accessor ambiguous) |
| `DB_DAO_SCOPE_UNRESOLVED` | DAO scope context could not be resolved |
| `DB_METHOD_BODY_UNSUPPORTED` | Method body uses an unsupported construct (e.g., expression body) |
| `DB_ROOM_INVENTORY_WRITE_FAILED` | Inventory report could not be written to disk |
| `INVENTORY_DURABILITY_UNCONFIRMED` | Inventory write durability could not be confirmed |

These are defined in `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` §6.2.

---

## 4. Core trust contract

### 4.1 Inventory untrusted when any infrastructure diagnostic is present

> **If any `DB_ROOM_*` or related infrastructure diagnostic exists in an
> inventory run, the entire inventory is untrusted for authorization
> purposes.**

This applies even if:

- only one declaration out of hundreds failed to resolve;
- the failure is in a rarely-used DAO;
- the scanner continued and produced entries for other declarations.

The inventory is an **all-or-nothing** trust unit. A single infrastructure
diagnostic poisons the whole.

### 4.2 Mutators with diagnostics are context only

Any `RoomMutator` entries present in an inventory report that also carries
at least one infrastructure diagnostic are **diagnostic context only**. They
must **not** be consumed as:

- authorized mutations;
- policy decisions;
- baseline entries;
- ratchet comparison inputs;
- triage classifications.

They exist solely to help a human reviewer understand what the scanner
*did* resolve before the infrastructure failure occurred.

### 4.3 Consumers must reject the run (exit 2)

Every consumer of inventory output MUST:

1. Check the report for any infrastructure diagnostic entry.
2. If found, **reject the entire run** — exit `2` with a controlled reason
   code.
3. **Not** use partial inventory results for any policy, baseline, ratchet,
   or authorization decision.
4. **Not** emit a partial success or "N new / M resolved" summary.

The consumer's exit code must be `2` (infrastructure error), not `0`
(pass) or `1` (violation). Partial success is never an acceptable
outcome.

### 4.4 Trusted empty inventory

An inventory with **zero mutators** and **zero infrastructure diagnostics**
is a valid, trusted empty inventory. It means:

- the scanner completed fully;
- no DAO declarations were found (or all were read-only);
- the result is trustworthy.

This is categorically different from an inventory with zero mutators
**and** one or more infrastructure diagnostics (e.g.,
`DB_ROOM_QUERY_UNCLASSIFIABLE`). The latter is **untrusted** because the
scanner may have failed to discover mutators due to the diagnostic.

| Scenario | Mutators | Diagnostics | Trust status | Exit code |
|----------|----------|-------------|--------------|-----------|
| Valid empty inventory | 0 | 0 | **Trusted** | 0 |
| Diagnostic-bearing inventory | >= 0 | >= 1 | **Untrusted** | 2 |
| Full valid inventory | > 0 | 0 | **Trusted** | 0 or 1 |

---

## 5. Consumer decision tree

```
Inventory report received
  |
  +-- Any infrastructure diagnostic present?
  |   +-- YES --> EXIT 2 (infrastructure error)
  |   |          Do NOT consume mutators as findings.
  |   |          Do NOT update ratchet/baseline.
  |   |          Do NOT emit authorization success.
  |   |
  |   +-- NO  --> Continue to ratchet/policy evaluation
  |               (report is trusted)
  |
  +-- Mutator count == 0 AND diagnostic count == 0?
      +-- YES --> Valid empty inventory. Exit 0.
      +-- NO  --> Process findings normally.
```

---

## 6. Source-root contract

`build_room_inventory(source_root, raw_query_policy=None)` scans exactly
one approved Kotlin production source root:

- `app/src/main/java` is the only root ever walked.
- The caller may pass the project root, `app/src`, `app/src/main`, or
  `app/src/main/java` itself; all four normalize to the same canonical
  production inventory.
- Test, androidTest, debug, and release source roots are **never**
  inventoried, even when they contain valid Room DAO annotations.
- Generated/build output roots are never inventoried.

Fail-closed source handling:

| Condition | Outcome |
|-----------|---------|
| Non-directory / non-approved root | `DB_ROOM_INVALID_SOURCE`, empty inventory |
| Directory walk cannot be read | `DB_ROOM_SOURCE_UNREADABLE`, empty inventory (no partial success) |
| No Kotlin sources or no DAOs under the root | `DB_ROOM_SOURCE_EMPTY`, empty inventory |
| A source file cannot be read | `DB_ROOM_SOURCE_UNREADABLE:<path>`, empty inventory |

Filename and class suffix are never evidence: DAOs are discovered by their
`@Dao` declaration (including qualified annotation spellings), and a DAO
declared in a file not ending in `Dao.kt` is inventoried normally.

---

## 7. Mutator discovery

Direct Room annotations are always mutating regardless of the declared
method name:

- `@Insert`, `@Update`, `@Delete`, `@Upsert` -> `ROOM_INSERT`,
  `ROOM_UPDATE`, `ROOM_DELETE`, `ROOM_UPSERT`.
- `@Query` SQL is extracted (including same-file `const val` template
  resolution) and classified by the SQL classifier
  (`scripts/db_guard/sql_classifier.py`); mutating SQL ->
  `ROOM_MUTATING_QUERY`.
- `@RawQuery` is classified **only** through the exact policy below.
- DAO inheritance is resolved as a fixed-point graph with cycle detection;
  unresolved or ambiguous parents fail closed.
- Default DAO body wrappers that call a mutator are classified
  `ROOM_TRANSITIVE_WRAPPER` in the full D2 design; the current D2 stage
  inventories direct and inherited mutators.

### 7.1 Annotation-to-declaration association span

`@Dao` annotations are associated with their declarations through a
**bounded structural span**, never through an arbitrary character window:

- The search for a declaration's `@Dao` annotation starts at the enclosing
  scope boundary (the scope-opening `{` that directly contains the
  declaration, or file start for top-level declarations) and stops at the
  declaration header itself.
- Only annotations separated from the declaration by legal whitespace,
  further annotations, or modifiers (i.e. adjacent per the accessor's
  adjacency contract) can decorate it.
- A legal annotation-to-declaration span is **documented safe up to
  `MAX_ANNOTATION_TO_DECLARATION_SPAN` (16384 characters)** in
  `scripts/db_guard/dao_accessors.py`. A legal span larger than this
  documented maximum is **not silently skipped**: the run fails closed with
  the controlled `DB_DAO_ANNOTATION_SCOPE_UNRESOLVED` infrastructure
  diagnostic instead of omitting the DAO.

---

## 8. `@RawQuery` exact policy

Canonical production policy: `config/guards/db_raw_query_classification.yml`

- Schema `version: 1`, a `methods` list.
- Each entry is an exact callable identity:
  - `dao` — exact DAO FQCN;
  - `method` — exact method name;
  - `signature` — exact receiver and parameter types
    (`receiver: null`, canonical resolved parameter list);
  - `classification` — `read` or `write`;
  - `reason`, `owner`, `linked_issue` — controlled documentation fields.
- Wildcard values (`*`, `%`, `...`, `any`, `all`), extra fields, expiry
  fields, and duplicate canonical keys are rejected.
- The default inventory lookup always reads the canonical config; fixture
  policies under `scripts/fixtures/` are used only when a caller passes them
  explicitly.

Enforcement:

- A discovered `@RawQuery` with no matching policy entry ->
  `DB_ROOM_RAW_QUERY_POLICY_REQUIRED` (fail closed, never a guessed read).
- A policy entry with no discovered counterpart ->
  `DB_ROOM_RAW_QUERY_POLICY_STALE` (fail closed; the policy is no longer
  truthful for the source tree).
- A malformed, wildcarded, or duplicate policy ->
  `DB_ROOM_RAW_QUERY_POLICY_INVALID` (fail closed). Stale entries are
  reported with `DB_ROOM_RAW_QUERY_POLICY_STALE`, never `INVALID`.
- `read`-classified raw queries are never inventory mutators;
  `write`-classified raw queries become `ROOM_MUTATING_QUERY` mutators.
- Signature resolution resolves the query parameter to the canonical
  spelling (e.g. `SupportSQLiteQuery` ->
  `androidx.sqlite.db.SupportSQLiteQuery`) through exact imports; wildcard
  or ambiguous imports fail closed with `DB_SIGNATURE_UNRESOLVED`.

### `@RawQuery` signature contract

A valid `@RawQuery` must declare exactly one parameter whose canonical type
resolves to the exact `androidx.sqlite.db.SupportSQLiteQuery` FQCN, and
must have no receiver:

- A resolvable-but-unsupported signature — a wrong parameter count, a wrong
  parameter type (`String`, `Object`, a generic/container type, or a
  nullable type), or an extension receiver — fails closed with
  `DB_ROOM_RAW_QUERY_POLICY_INVALID`.
- An unresolvable parameter (an unknown simple name, a wildcard import, or
  an ambiguous import) fails closed with `DB_SIGNATURE_UNRESOLVED`.
- Contract-violating methods are never policy identities and never mutators,
  even when a write-classified policy entry appears to match their exact
  callable signature; such an entry is reported `STALE` because it has no
  discovered counterpart.

### Global bidirectional equality contract

The inventory derives the **complete** discovered production `@RawQuery`
identity set from **every** discovered DAO (not only `ExpenseDao`) and
compares it bidirectionally against the canonical policy key set:

- `discovered - policy` -> `DB_ROOM_RAW_QUERY_POLICY_REQUIRED` (each
  unlisted discovered identity fails closed);
- `policy - discovered` -> `DB_ROOM_RAW_QUERY_POLICY_STALE` (each
  policy-only entry fails closed);
- duplicate policy keys/classifications/signatures ->
  `DB_ROOM_RAW_QUERY_POLICY_INVALID` (the loader rejects them before the
  set comparison runs).

The comparison runs only when the policy loaded successfully and at least
one DAO was discovered; a malformed policy
(`DB_ROOM_RAW_QUERY_POLICY_INVALID`) and an empty source
(`DB_ROOM_SOURCE_EMPTY`) already fail closed and never participate.
Test/fixture policies are never loaded by default: the default lookup
always reads `config/guards/db_raw_query_classification.yml`, and fixture
policies under `scripts/fixtures/` are used only when a caller passes them
explicitly.

The discovered set is derived **after DAO inheritance fixed-point
resolution**, so it is the effective identity set, not only the direct
`@RawQuery` declarations:

- Every child DAO exposing an inherited `@RawQuery` contributes a new
  identity owned by the child DAO (child FQCN in the policy key) with the
  same exact method/signature/receiver/params and `inherited_from` metadata
  recording the immediate parent through which it flowed.
- The canonical policy must therefore carry a **child-DAO entry** for every
  effective inherited method; a declaration-only (parent) entry is never
  silently assumed to cover the child — the child identity fails closed with
  `DB_ROOM_RAW_QUERY_POLICY_REQUIRED`.
- A child's own declaration of the same callable identity replaces the
  inherited one (it is not assumed to still be the parent's `@RawQuery`); a
  policy child entry with no claimed counterpart fails closed with
  `DB_ROOM_RAW_QUERY_POLICY_STALE`.
- An inherited identity exposed by **multiple parents** is ambiguous: the
  child identity is never claimed as an exact policy match and
  `DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS` is emitted.
- DAOs with unresolved, cyclic, or duplicate inheritance never claim
  inherited identities (their direct identities are preserved), and the DAO
  inheritance graph already emitted the controlled
  `DB_DAO_INHERITANCE_*` diagnostics for those cases.

#### Effective RawQuery mutator derivation

RawQuery mutators (direct **and** inherited) are derived from the same
effective identity set **after** direct declarations and inheritance are
finalized, and every effective identity is evaluated with its **child-owned**
policy classification — never with the declaring parent's:

- `read`-classified effective identity -> no mutator. A child's own
  read-classified `@RawQuery` (or any non-RawQuery child declaration of the
  same callable) therefore shadows an inherited write-classified parent
  callable and no inherited mutator is emitted for the child.
- `write`-classified effective identity -> one `ROOM_MUTATING_QUERY` mutator
  whose identity is owned by the effective identity's DAO: the child FQCN
  and child canonical path in the signature, with `inherited_from` recording
  the immediate parent through which the identity flowed. The parent's
  classification is never copied onto the child.
- Missing child-owned policy entry -> `DB_ROOM_RAW_QUERY_POLICY_REQUIRED` and
  no mutator for that identity (fail closed), even when a write-classified
  parent declares the callable.
- Ambiguous inherited identity (multiple parents) ->
  `DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS`, the identity is never
  claimed, and no mutator is emitted for it.

A child direct declaration shadows the inherited parent callable
**regardless of annotation, read/write classification, or policy coverage**:
the inherited identity is removed from the child's effective set, so no
inherited mutator can be emitted for an identity the child declares (whether
the child's declaration is itself a mutator, a read, or a non-RawQuery
method).

`DB_ROOM_RAW_QUERY_UNCLASSIFIABLE` is intentionally **not** an inventory
diagnostic: RawQuery has no SQL to classify (the dynamic
`SupportSQLiteQuery` contract is the only supported signature), and the
policy classification is validated to be exactly `read` or `write` at load
time. Invalid or missing policy classification fails closed with
`DB_ROOM_RAW_QUERY_POLICY_INVALID` / `DB_ROOM_RAW_QUERY_POLICY_REQUIRED`
only. SQL/template uncertainty for `@Query` remains
`DB_ROOM_QUERY_UNCLASSIFIABLE`.

The ExpenseDao-specific production contract
(`test_production_root_raw_query_contract_resolves_expense_dao`) is a
**subset** of this global equality contract: production has no
`DB_ROOM_RAW_QUERY_POLICY_REQUIRED` / `STALE` / `INVALID` diagnostics
because the canonical policy exactly equals the discovered set across every
production DAO.

Production contract test:
`scripts/test_db_guard_room_inventory.py::test_production_root_raw_query_contract_resolves_expense_dao`

The contract derives every discovered ExpenseDao `@RawQuery`
identity/signature from the actual production inventory, loads the canonical
policy, and requires **exact bidirectional set equality**:

- every discovered ExpenseDao `@RawQuery` has exactly one policy entry;
- every policy ExpenseDao entry is a discovered `@RawQuery`;
- all are `read`-classified (policy says read and the inventory never
  classifies them as mutators);
- no `DB_ROOM_RAW_QUERY_POLICY_REQUIRED` /
  `DB_ROOM_RAW_QUERY_POLICY_STALE` / `DB_ROOM_RAW_QUERY_POLICY_INVALID` /
  `DB_SIGNATURE_UNRESOLVED` / duplicate / ambiguous diagnostics are
  reported for production ExpenseDao.

Global production contract test:
`scripts/test_db_guard_room_inventory.py::test_production_root_raw_query_global_set_exact_equality`

It scans every discovered production DAO, derives every `@RawQuery`
identity exactly as the inventory resolves signatures, and requires the
complete discovered set to equal the complete canonical policy key set
bidirectionally, with no raw-query/policy diagnostics globally.

If the production ExpenseDao source or the canonical policy is missing, the
test fails with a controlled
`PRODUCTION_RAW_QUERY_CONTRACT_FIXTURE_UNAVAILABLE` assertion instead of
silently skipping.

---

## 9. Scanner obligations

The scanner (`room_inventory.py`, `verify_db_access_boundaries.py` in
inventory mode) MUST:

1. **Emit all infrastructure diagnostics** to the structured report. Do not
   suppress or downgrade them.
2. **Set the report-level status** to indicate infrastructure failure when
   any diagnostic is present.
3. **Continue scanning** remaining declarations for diagnostic completeness,
   but never change the final exit code from `2` when a diagnostic exists.
4. **Not** write a partial inventory to `--dump-room-mutators` output unless
   the consumer is explicitly designed to handle incomplete data (e.g.,
   `--dump-room-mutators` for human review only).

---

## 10. Ratchet/CI obligations

The guard ratchet and CI pipeline MUST:

1. Parse the structured report (not stdout text).
2. Inspect the report for infrastructure diagnostic entries before performing
   any ratchet comparison.
3. If diagnostics are present, fail the CI step with exit `2` and a message
   like:
   ```
   DB infrastructure diagnostics present — inventory untrusted for authorization.
   N diagnostic(s): [list of codes]
   ```
4. **Not** record the inventory as a ratchet baseline update.
5. **Not** compute "new keys" or "resolved keys" from a diagnostic-bearing
   inventory.

---

## 11. Pre-existing DDL classifier limitation / failure — NOT baselined

The SQL classifier (`scripts/db_guard/sql_classifier.py`) recognizes
`ALTER` as a mutating keyword and validates a **bounded DDL subset**. For
`ALTER TABLE ... ADD COLUMN ...` the accepted form is:

```text
ALTER TABLE <table> ADD [COLUMN] <name> <type> [constraints]
```

where the column definition must include a type and only the documented
constraint subset (`PRIMARY KEY`, `NOT NULL`, `UNIQUE`, `CHECK`, `DEFAULT`,
`COLLATE`, `REFERENCES`, `CONSTRAINT`, `GENERATED`).

The following `ALTER TABLE ... ADD COLUMN` forms are **pre-existing DDL
classifier limitations**: they are outside the bounded subset and are
reported as `DB_ROOM_QUERY_UNCLASSIFIABLE` (fail closed), so any Room
`@Query` carrying them surfaces an infrastructure diagnostic instead of a
confident mutation finding:

- missing column type — `ALTER TABLE t ADD COLUMN c`
- assignment-shaped definition — `ALTER TABLE t ADD COLUMN c = 1`
- unsupported column constraint — `ALTER TABLE t ADD COLUMN c TEXT ON CONFLICT IGNORE`
- table-level ADD constraint — `ALTER TABLE t ADD CONSTRAINT u UNIQUE (c)`

This limitation is **not baselined and not suppressed**. It remains a
follow-up to widen the bounded column-definition grammar. It is fail-closed
in both directions: it never invents a read (a mutation cannot be missed),
but it cannot confirm the statement as a mutation either.

---

## 12. Other pre-existing production `@Query` classifier limitations — NOT baselined

A production inventory run over `app/src/main/java` currently surfaces
`DB_ROOM_QUERY_UNCLASSIFIABLE` infrastructure diagnostics for several
`ExpenseDao` `@Query` methods (the exact count changes with the source).
These are **pre-existing** SQL/template classifier limitations in the D2
stage, fail-closed, and **not baselined or suppressed**; they remain
follow-ups:

- Multiline `+`-joined `const val` continuations are supported when the
  first fragment starts on the declaration line (the production-style
  `const val A = "SELECT ..."` + `" WHERE ..."` form resolves and
  classifies correctly; see
  `test_query_template_resolves_multiline_const_continuation` in
  `scripts/test_db_guard_room_inventory.py`). The remaining template
  limitation is the shape whose RHS begins on the line *after* the `=`
  (for example `EFFECTIVE_AMOUNT_SQL` in `ExpenseDao.kt`): such a constant
  is not collected, so every
  `@Query("...${EFFECTIVE_AMOUNT_SQL}...")` template fails closed as
  `DB_ROOM_QUERY_UNCLASSIFIABLE`.
- Empty-parentheses function calls in a `SELECT` projection (for example
  `SELECT changes()`) are not accepted by the bounded expression grammar
  and fail closed as `DB_ROOM_QUERY_UNCLASSIFIABLE`; function calls with
  an argument (for example `SELECT COUNT(*) ...`) are accepted.

None of these are `@RawQuery` limitations: the three production ExpenseDao
`@RawQuery` methods resolve as reads through the exact policy (see
section 8). These `@Query` diagnostics are also never raw-query
diagnostics, so the production RawQuery contract test does not treat them as
failures — they are tracked here as known follow-ups instead of being
baselined or suppressed.

---

## 13. Limitations and known gaps

- The `--inventory-only` flag currently emits to stdout in some code paths.
  Until structured output is the sole path, consumers must parse JSON and
  reject any non-JSON output as infrastructure failure.
- The diagnostic code set may grow as new Room constructs are supported.
  Consumers must treat **any** unknown diagnostic code as infrastructure
  failure (fail closed).
- The trust boundary applies to the **current scanner run only**. A
  previous successful inventory run does not authorize a subsequent
  diagnostic-bearing run.
- D2 does not modify the active DB ownership policy
  (`config/guards/db_ownership_policy.yml`), the DB baseline
  (`config/baselines/db_access.json`), or the structural exceptions policy.
  D1 dependency files (`scripts/db_policy_signature.py`,
  `scripts/kotlin_callable_parser.py`, `scripts/__init__.py`,
  `scripts/db_guard/__init__.py`) are owned by the separate D1 dependency
  commit and are out of scope here.

---

## 14. Regression reminder

- Any change to a production `@RawQuery` method (add, remove, rename, or
  signature change) requires the canonical policy
  (`config/guards/db_raw_query_classification.yml`) and the production
  contract test to be updated together; the contract test must pass with a
  real inventory run over `app/src/main/java`.
- Any change to a production `@Dao` declaration or Room annotation must be
  re-validated against the inventory contract tests.
- Do not mark D2 (or this document) complete until: the production contract
  test passes with a real run; the DDL `ALTER TABLE ... ADD COLUMN`
  limitation follow-up is resolved or explicitly re-reviewed; and the
  reviewer gate passes.

---

## 15. Status

| Item | Status |
|------|--------|
| Infrastructure diagnostic codes defined | PARTIAL (codes listed, scanner integration pending PR-D2) |
| Scanner emits diagnostics to structured report | PENDING (not yet implemented) |
| Ratchet checks for diagnostics before comparison | PENDING (not yet implemented) |
| CI pipeline rejects diagnostic-bearing inventory | PENDING (not yet implemented) |
| `@RawQuery` exact policy contract | PARTIAL (policy file exists, bidirectional equality tests exist) |
| Source-root contract | PARTIAL (implemented, not CI-gated) |
| SQL DDL `ALTER TABLE ... ADD COLUMN` limitation | PENDING (not baselined, not resolved) |
| `@Query` template limitation (RHS-on-next-line) | PENDING (not baselined, not resolved) |
| `@Query` empty-paren function call limitation | PENDING (not baselined, not resolved) |
| This trust contract document | **PARTIAL / PENDING REVIEW** |

---

## 16. References

- `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` — §6.2 (infrastructure
  codes), §9 (PR-D2), §12.1 (final inventory)
- `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` — Gate FG-03 (fail-closed detector
  contract)
- `GUARD_FINDING_DB_V2_LEDGER.md` — Migration ledger
- `guard-framework.md` — Exit code semantics
- `guard-policy.md` — Fail-closed rules
