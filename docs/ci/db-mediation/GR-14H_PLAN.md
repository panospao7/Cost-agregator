# GR-14h execution plan — entry family tranche 1: Pattern E dead-writer removal

Status: PLANNED (not started — this document is the reviewed execution
recipe; evidence gathered during GR-14f/g, see `build/guard-debug/gr14f/`).

## Family state after GR-14f/g

Entry family (`GR13_ZERO_INBOUND_CALL_SITES`): 68 rows over ~55 callables
(64 baseline + 4 precision-corrected zero-inbound rows from GR-14f).
Tranche 1 removes the three callables whose zero-inbound status was
independently verified as genuinely dead (grep: zero production call
sites; engine: zero exact/uncertain inbound after precise resolution).

## Tranche 1 removal set (4 policy mutation keys — within the 10-key cap)

| Callable | Rows | Evidence |
|---|---|---|
| `WarrantyTrackerRepository.addReturnWindow` | 1 (`returnWindowDao.insertReturnWindow`) | grep: only a barrier-check string mention; zero call sites |
| `TransactionLifecycleCoordinator.writeUpdateValidationFailedEventBestEffort` | 1 (`transactionEventDao.insert`) | grep: zero call sites besides the definition |
| `TransactionLifecycleCoordinator.bulkUpdateCategory(Long,Long,String)` overload | 2 (`expenseDao.updateCategoryForMerchant`, `transactionEventDao.insert`) | sole production call site (`ExpenseRepository.updateExpenseCategoryBulk` line ~478) resolves exactly to the `(String,Long,String,...)` overload; the `(Long,Long,String)` overload has zero call sites |

Removal is compile-provable: if any caller existed, `compileDebugKotlin`
fails.  DI/reflection entry is impossible (these are injected-constructor
class members, not reflective surfaces).

## Execution steps (in order; stop on any mismatch)

1. **Remove the dead code** (production Kotlin only): the three callables
   above.  No other edits.
2. **Compile**: `./gradlew :app:compileDebugKotlin --console=plain` —
   success IS the caller-inventory proof for step 3 of Pattern E.
3. **Regenerate the v2 candidate policy** through the GR-08/GR-09 tooling
   so the scanner naturally drops the 4 rows (candidate 475 -> 471).
   Do NOT hand-edit `config/guards/db_ownership_policy.yml` — it is hashed
   into the GATE-00R capture matrix (`capture_db_guard_evidence.py`).
4. **Compare mutation-key delta**: exactly `-4` keys (the removal set).
   Any other delta = stop and review.
5. **Promote through the controlled CLI** (`promote_db_policy_v2.py` with
   `--force-repromote` since active is already v2): requires fresh GR-06
   evidence report (`verify_db_policy_v2_evidence.py`, trusted, sha-matched
   to the candidate) and the GR-05 accounting crosswalk.  All gates must
   pass before any write.
6. **Shadow re-run**: entry family 68 -> 64; every other proof state,
   diagnostics, and mutation-key set unchanged.  Deterministic double-run
   byte-identical (adversarial #10).
7. **Focused tests** for touched files (WarrantyTrackerRepository /
   TransactionLifecycleCoordinator) + full affected unit classes.
8. **Manifest** `GR-14h.yml` (status PARTIAL; GATE-00R + Gradle DB owed at
   merge — the policy change makes the GATE-00R recapture MANDATORY for
   this batch, not just carried debt).

## Later entry-family tranches (GR-14i+)

Remaining ~64 rows over ~52 callables split into:
- **alive-with-callers rows** (edges are uncertain from ViewModels/UI
  launch blocks, e.g. `ReviewQueueRepository.approveReview`): remediation
  is caller-side barrier/proof work in the async-dispatch surface, NOT
  entry dispositions — these merge into the async family workstream.
- **framework-entry rows** (callbacks invoked by the SDK, e.g. DAO
  triggers): require documented entry dispositions per the GR-13 plan.
- **further dead candidates**: require the same grep + engine +
  compile-proof triple before joining a removal tranche.
