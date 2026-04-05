# Batch 15: Location, OCR, Groups fixes (H7, H8, H9)

## Technical Plan (Advanced)

### Scope
- In:
  - **H7**: close member-deletion invariant bypass in `SharedExpenseManager` (`SharedExpenseManager.kt:78-79`)
  - **H8**: enforce non-finite validation for custom splits in manager/parser path (`SharedExpenseManager.kt:95-113`, parser call chain)
  - **H9**: fix test compile failure in `SharedExpenseTest.kt:209-210`
  - Add/adjust unit tests to lock behavior.
- Out:
  - New feature work for Location/OCR pipelines
  - Schema migrations
  - UI redesign/refactor of Groups screens
  - Large architecture reshaping of repository/domain boundaries

### Complexity Assessment
- Estimated files touched: **4-7**
  - `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/logic/CustomSplitParser.kt`
  - `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/logic/CustomSplitParserTest.kt`
  - (optional, if contract/result type broadened) `SharedExpensePort.kt` and/or additional test files
- Risk level: **medium**
- Cross-module impact: **yes** (domain groups + parser + tests)

### Batch Plan
1. Batch name: **H9 Build Unblock – fix MockK verification syntax**
   - files:
     - `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseTest.kt`
   - objective:
     - Restore test compilation by replacing invalid matcher usage at lines 209-210.
   - root cause analysis:
     - Current code uses unresolved `io.mockk.withArg` in `coVerify`:
       ```kotlin
       sharedExpenseDataPort.addExpense(io.mockk.withArg { inserted ->
           assertEquals("USD", inserted.currency)
       })
       ```
     - In this project’s MockK setup/version, this reference does not resolve, causing compilation failure:
       - `Unresolved reference 'withArg'`
       - type inference fallout for `inserted`
   - implementation strategy:
     1. Replace the failing verification with a supported matcher/capture pattern:
        - `match { it.currency == "USD" }`, or
        - capture to `slot<SharedGroupExpense>()` then assert afterward.
     2. Keep assertion semantics unchanged: verify that manager forwards **group currency** (`USD`) rather than input currency (`EUR`).
     3. Keep test name and scenario unchanged.
   - dependencies:
     - No functional dependency on H7/H8.
     - Should be executed **first** to restore fast feedback from CI/local tests.
   - risk assessment:
     - Risk: false-positive verification if matcher is too loose.
     - Mitigation: verify exact argument call count and explicit `currency == "USD"` predicate.
   - verification plan:
     - `:app:compileDebugUnitTestKotlin` must pass.
     - Run focused test: `SharedExpenseTest`.
   - estimated effort: **Low**
   - risks:
     - Minimal; confined to test code.
   - validation:
     - Build compiles and targeted test passes.

2. Batch name: **H8 Non-finite custom split hardening**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
     - `app/src/main/java/com/yourname/expensetracker/domain/logic/CustomSplitParser.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/logic/CustomSplitParserTest.kt`
     - (optional) `SharedExpenseTest.kt` for manager-level rejection tests
   - objective:
     - Ensure NaN/Infinity values cannot pass custom split validation and be persisted.
   - root cause analysis:
     - `SharedExpenseManager.addExpense()` serializes `customSplits` and delegates validation (`95-113`) but does not explicitly reject non-finite doubles.
     - Parser currently does:
       - `toDoubleOrNull()` parse
       - negative check (`value < 0.0`)
       - sum/tolerance checks
     - For `NaN`, comparisons like `abs(sum - expected) > tolerance` evaluate false, enabling invalid payloads to be treated as valid.
   - implementation strategy:
     1. **Parser-level invariant (primary):** in `CustomSplitParser.parseAndValidate`, reject:
        - non-finite `totalAmount`
        - non-finite split `value`
     2. **Manager-level guard (defense-in-depth):** before serialization/validation in `SharedExpenseManager.addExpense`, reject non-finite values in incoming `customSplits` map for non-equal modes.
     3. Keep error messages deterministic and actionable (e.g., “split values must be finite”).
     4. Add parser tests for:
        - NaN value
        - +Infinity value
        - non-finite totalAmount
     5. Add/adjust manager test ensuring `addExpense` throws `IllegalArgumentException` on non-finite custom split input.
   - dependencies:
     - Independent from H7; can proceed in parallel once H9 compile blocker is removed.
   - risk assessment:
     - Risk: stricter parser may reject previously persisted malformed legacy data when reading.
     - Mitigation: read paths already convert invalid parse into fallback behavior in split calculators/manager; preserve fallback semantics and only hard-fail on **new writes**.
   - verification plan:
     - Unit: parser tests cover NaN/Infinity/non-finite totals.
     - Unit: manager addExpense test verifies exception on non-finite custom splits.
     - Regression: existing valid custom percent/amount tests remain green.
   - estimated effort: **Medium**
   - risks:
     - Behavior tightening may expose hidden bad fixtures.
   - validation:
     - New tests pass; valid flows unchanged; invalid finite-edge inputs rejected.

3. Batch name: **H7 Member deletion invariant enforcement in SharedExpenseManager**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
     - (optional for shared logic) `SharedExpensePort.kt` / result type declarations
     - `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseTest.kt` (or dedicated manager tests)
   - objective:
     - Prevent `SharedExpenseManager.removeMember()` from bypassing deletion safety invariants.
   - root cause analysis:
     - Current manager implementation is a direct passthrough:
       ```kotlin
       suspend fun removeMember(member: SharedExpenseMember) {
           sharedExpenseDataPort.removeMember(member)
       }
       ```
     - This bypasses richer checks present elsewhere (`GroupsRepositoryImpl.deleteMember`) such as:
       - member paid expense references
       - custom split references
     - Impact:
       - inconsistent behavior across deletion entry points
       - potential orphaned split references / incorrect downstream calculations
       - possible DB-level constraint errors in some cases (payer FK restrict), but not full business-rule coverage
   - implementation strategy:
     1. Define manager-side pre-delete validation routine (single place in `SharedExpenseManager`).
     2. Enforce minimum invariants before data-port delete:
        - member exists in group
        - member is not payer in existing group expenses
        - member is not referenced in any custom splits (use `CustomSplitParser.referencesMember` with parser result)
     3. Return/throw deterministic failure (prefer explicit domain exception or typed result; keep API stable if possible).
     4. Call `sharedExpenseDataPort.removeMember(member)` only after invariant pass.
     5. Add tests:
        - blocks deletion when payer references exist
        - blocks deletion when custom split references exist
        - allows deletion when no references exist
        - verifies data-port delete is not called on rejected cases
   - dependencies:
     - No hard dependency on H8/H9.
     - **Assumption dependency:** business rule authority for “last member/current user deletion” must be confirmed (currently not consistently enforced).
   - risk assessment:
     - Risk: behavior change may block workflows previously allowed through manager path.
     - Mitigation:
       - align error messages with existing repository semantics
       - add focused tests for old-allowed/new-blocked paths
       - communicate expected contract in KDoc.
   - verification plan:
     - Unit tests for all block/allow paths.
     - Negative verification: no call to data-port `removeMember` when invariant fails.
     - Regression: existing balance/split tests unchanged.
   - estimated effort: **Medium**
   - risks:
     - Potential disagreement on exact invariant set.
   - validation:
     - Manager path can no longer remove members referenced by expenses/splits.

### Dependencies
- Recommended execution order:
  1. **H9 first** (restore compilation feedback loop)
  2. **H8** and **H7** next (independent functional fixes)
- Logical dependencies:
  - H8 parser hardening benefits H7 split-reference checks (more reliable parse outcomes), but H7 can still be implemented without waiting.
- Assumptions/unknowns to resolve before coding:
  - Should manager deletion also enforce “cannot delete last member” and/or “cannot delete current user member”?
  - Should deletion failures be exceptions or typed domain results?

### Rollback / Safety
- Keep each issue in a separate commit for selective rollback.
- Prefer additive/internal guards over broad interface rewrites.
- If stricter validation causes unexpected failures:
  - temporarily gate with clear error messages,
  - keep read-path fallback behavior for legacy malformed split payloads.
- For H7, preserve existing data-port contract where possible to avoid cascading breakage.

### Acceptance Criteria
- [ ] `SharedExpenseTest.kt` compiles; `withArg`-related errors are eliminated (H9).
- [ ] Non-finite custom split values (`NaN`, `Infinity`) are rejected by validation for write paths (H8).
- [ ] `CustomSplitParser` rejects non-finite totals/values and has dedicated unit coverage (H8).
- [ ] `SharedExpenseManager.removeMember()` enforces deletion invariants before delegating to data port (H7).
- [ ] Manager deletion tests verify both blocked and successful delete scenarios (H7).
- [ ] Existing valid custom split and balance tests remain green (no regression).
