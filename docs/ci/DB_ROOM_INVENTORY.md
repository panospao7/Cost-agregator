# DB Room Mutator Inventory — Trust and Diagnostic Contract

**Status:** PARTIAL / PENDING REVIEW
**Plan:** `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` (PR-D2)
**Branch:** `guard-finding-db-discovery-v2`
**Last updated:** 2026-08-14

---

## 1. Purpose

This document defines the trust boundary between a **DB_ROOM_** inventory run and any downstream consumer (ratchet, baseline, policy decision, triage). It establishes the contract that infrastructure diagnostics in an inventory report render the entire inventory **untrusted for authorization**.

---

## 2. Scope

Applies to:

- `scripts/db_guard/room_inventory.py` (Room mutator inventory)
- `scripts/verify_db_access_boundaries.py` when operating in `--inventory-only` or `--dump-room-mutators` mode
- `scripts/ci/guard_ratchet.py` when consuming inventory output for DB guard authorization
- Any downstream consumer of `build/reports/db-guard/room-mutator-inventory.json`

---

## 3. Infrastructure diagnostic codes

The following codes are **DB_ROOM_** infrastructure diagnostics. They indicate the scanner could not fully resolve the Room inventory for a declaration, DAO, or query.

| Code | Meaning |
|------|---------|
| `DB_ROOM_QUERY_UNCLASSIFIABLE` | A `@Query` SQL statement could not be tokenized or classified as read/write |
| `DB_DAO_INHERITANCE_UNRESOLVED` | DAO inheritance graph has a cycle, missing parent, or ambiguous parent |
| `DB_SIGNATURE_UNRESOLVED` | Exact callable signature could not be determined (overload ambiguity) |
| `DB_CALL_TARGET_AMBIGUOUS` | Call-site target resolution failed (receiver or accessor ambiguous) |
| `DB_DAO_SCOPE_UNRESOLVED` | DAO scope context could not be resolved |
| `DB_METHOD_BODY_UNSUPPORTED` | Method body uses an unsupported construct (e.g., expression body) |
| `DB_SOURCE_UNREADABLE` | Source file could not be read |
| `DB_POLICY_SOURCE_EVIDENCE_INVALID` | Policy-source evidence is malformed or missing |

These are defined in `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` §6.2.

---

## 4. Core contract

### 4.1 Inventory untrusted when any infrastructure diagnostic is present

> **If any `DB_ROOM_*` or related infrastructure diagnostic exists in an inventory run, the entire inventory is untrusted for authorization purposes.**

This applies even if:

- only one declaration out of hundreds failed to resolve;
- the failure is in a rarely-used DAO;
- the scanner continued and produced entries for other declarations.

The inventory is an **all-or-nothing** trust unit. A single infrastructure diagnostic poisons the whole.

### 4.2 Mutators in a diagnostic-bearing inventory are context only

Any `RoomMutator` entries present in an inventory report that also carries at least one infrastructure diagnostic are **diagnostic context only**. They must not be consumed as:

- authorized mutations;
- policy decisions;
- baseline entries;
- ratchet comparison inputs;
- triage classifications.

They exist solely to help a human reviewer understand what the scanner *did* resolve before the infrastructure failure occurred.

### 4.3 Consumers must reject the run

Every consumer of inventory output MUST:

1. Check the report for any infrastructure diagnostic entry.
2. If found, **reject the entire run** — exit `2` with a controlled reason code.
3. **Not** use partial inventory results for any policy, baseline, ratchet, or authorization decision.
4. **Not** emit a partial success or "N new / M resolved" summary.

The consumer's exit code must be `2` (infrastructure error), not `0` (pass) or `1` (violation).

### 4.4 Distinction from valid zero-mutator inventory

An inventory with **zero mutators** and **zero infrastructure diagnostics** is a valid, trusted empty inventory. It means:

- the scanner completed fully;
- no DAO declarations were found (or all were read-only);
- the result is trustworthy.

This is categorically different from an inventory with zero mutators **and** one or more infrastructure diagnostics (e.g., `DB_ROOM_QUERY_UNCLASSIFIABLE`). The latter is **untrusted** because the scanner may have failed to discover mutators due to the diagnostic.

| Scenario | Mutators | Diagnostics | Trust status | Exit code |
|----------|----------|-------------|--------------|-----------|
| Valid empty inventory | 0 | 0 | **Trusted** | 0 |
| Diagnostic-bearing inventory | ≥0 | ≥1 | **Untrusted** | 2 |
| Full valid inventory | >0 | 0 | **Trusted** | 0 or 1 |

---

## 5. Scanner obligations

The scanner (`room_inventory.py`, `verify_db_access_boundaries.py` in inventory mode) MUST:

1. **Emit all infrastructure diagnostics** to the structured report. Do not suppress or downgrade them.
2. **Set the report-level status** to indicate infrastructure failure when any diagnostic is present.
3. **Continue scanning** remaining declarations for diagnostic completeness, but never change the final exit code from `2` when a diagnostic exists.
4. **Not** write a partial inventory to `--dump-room-mutators` output unless the consumer is explicitly designed to handle incomplete data (e.g., `--dump-room-mutators` for human review only).

---

## 6. Ratchet/CI obligations

The guard ratchet and CI pipeline MUST:

1. Parse the structured report (not stdout text).
2. Inspect the report for infrastructure diagnostic entries before performing any ratchet comparison.
3. If diagnostics are present, fail the CI step with exit `2` and a message like:
   ```
   DB infrastructure diagnostics present — inventory untrusted for authorization.
   N diagnostic(s): [list of codes]
   ```
4. **Not** record the inventory as a ratchet baseline update.
5. **Not** compute "new keys" or "resolved keys" from a diagnostic-bearing inventory.

---

## 7. Consumer decision tree

```
Inventory report received
  │
  ├─ Any infrastructure diagnostic present?
  │   ├─ YES → EXIT 2 (infrastructure error)
  │   │        Do NOT consume mutators as findings.
  │   │        Do NOT update ratchet/baseline.
  │   │        Do NOT emit authorization success.
  │   │
  │   └─ NO → Continue to ratchet/policy evaluation
  │           (report is trusted)
  │
  └─ Mutator count == 0 AND diagnostic count == 0?
      ├─ YES → Valid empty inventory. Exit 0.
      └─ NO  → Process findings normally.
```

---

## 8. Limitations and known gaps

- The `--inventory-only` flag currently emits to stdout in some code paths. Until structured output is the sole path, consumers must parse JSON and reject any non-JSON output as infrastructure failure.
- The diagnostic code set may grow as new Room constructs are supported. Consumers must treat **any** unknown diagnostic code as infrastructure failure (fail closed).
- The trust boundary applies to the **current scanner run only**. A previous successful inventory run does not authorize a subsequent diagnostic-bearing run.

---

## 9. Status

| Item | Status |
|------|--------|
| Infrastructure diagnostic codes defined | PARTIAL (codes listed, scanner integration pending PR-D2) |
| Scanner emits diagnostics to structured report | PENDING (not yet implemented) |
| Ratchet checks for diagnostics before comparison | PENDING (not yet implemented) |
| CI pipeline rejects diagnostic-bearing inventory | PENDING (not yet implemented) |
| This contract document | **PARTIAL / PENDING REVIEW** |

---

## 10. References

- `GUARDRAIL_FINDINGS_AND_DB_DISCOVERY_PLAN.md` — §6.2 (infrastructure codes), §9 (PR-D2), §12.1 (final inventory)
- `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` — Gate FG-03 (fail-closed detector contract)
- `GUARD_FINDING_DB_V2_LEDGER.md` — Migration ledger
- `guard-framework.md` — Exit code semantics
