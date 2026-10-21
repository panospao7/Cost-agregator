# B5-19 map and scope decision (scout 2026-10-18; orchestrator decision under standing approval)

File: `scripts/test_migrate_db_policy_seed_rows.py` (11,622 lines, 212 test functions). Production under test:
`scripts.db_guard.policy_model.match_mutation` and related code (imports :529-563).

## Plan corrections (from scout)
- The near-miss family is `test_gr08<x>_exact_identity_matches` plus `test_gr08<x>_near_miss_wrong_{overload,owner,dao,operation}_stays_unauthorized`. It covers 25 batches (b, c1, c2, d, e1, e2, f, g, h, i1, i2, i3, j1, j2, k1, k2, l1, l2, m1, m2, n1, n2, o, p1, p2) × 5 = 125 functions, plus gr08a's 6 (:1025-1080). There are no `test_batch_N_match_mutation_*` tests.
- There are 26 real-tracked row-count tests, not ~29. Each also asserts the methods multiset, per-entry path/owner/kind/receiver/barrier_mode/owner tag/linked_issue, key uniqueness, and some closure lists.
- `test_candidate_signatures_rejected_by_v2_loader` is not in this file. The nearest is `test_archived_v1_policy_rejected_by_v2_loader` in scripts/test_db_guard_policy_v2_current_repo.py:74, which is out of scope and untouched. This file has no advisory test.
- The "9 fields × mutate one" design would ADD items (26 × 10 = 260): kind, receiver, method and dao_fqcn-alone are never mutated today. It is out of scope for a reduction slice → B6 candidate.

## Decision (narrowed B5-19)
- Value: removes about 125 near-identical functions and about 25 near-identical helpers. Collected pytest items stay about the same (one id per current assertion), so the runtime gain is ≈ 0 and the gain is maintainability and file size.
- **DO:** replace the 131 match-family functions with ONE parametrized test. Each id is `"<batch>-<case>"`, plus `-<n>` for each extra assertion in a multi-assert test. Every current `is True` / `is False` assertion becomes exactly one table row, with the same rows factory, the same target selection and the same overrides. Keep the a/b overload derivation `target.parameter_types[:-1] + ("String?",)` as is: it is computed from the target in the table, which is acceptable, or pin the literal tuple. Use one shared `_policy_entries(rows)` helper (the per-batch variants are identical). Target selection is per-row data `(method, accessor, operation, parameters)`; batch g omits method. Every assert gets a message naming the id. Add a size guard on the table: the row count must equal the number of assertions removed.
- **KEEP unchanged:**
  - the 26 row-count tests (they carry shape asserts; a count-only table would weaken the guard);
  - the 37 multi-row near-miss/closure tests;
  - the loader/accounting/promotion tests :729-971;
  - the round trip :1083;
  - the reviewed-move tests :1448/:1476;
  - the combined test :7385.
- Delete a per-batch helper (`_gr08<x>_policy_entries`, `_assert_gr08<x>_exact_match`) only if nothing references it after conversion. Keep all `_gr08<x>_seed_rows` factories and constants. Do not dedupe redefined constants (call-time resolution; risky).
- B9-3 (later): move `_collect_accounting_mutation_keys` to about :942 ("(4) Promotion gate") and drop the import at :543-545.
- Expected: 212 → about 82 functions. Items: about 212 or a little more (multi-assert tests become one id per assertion).
- Execution: three sequential writers, each converting its batch range and saving after each batch. W1 builds the shared parametrized test and helper and converts a, b, c1, c2, d, e1, e2, f. W2 converts g, h, i1, i2, i3, j1, j2, k1, k2. W3 converts l1, l2, m1, m2, n1, n2, o, p1, p2 and removes dead helpers. Validation: `pytest-subset scripts/test_migrate_db_policy_seed_rows.py` (compare collected items before and after: baseline item count first) and also `scripts/test_db_guard_policy_v2_evidence.py` as a neighbour. Review: reviewer-strict (T2 guard policy) or Sonnet strict-style.
