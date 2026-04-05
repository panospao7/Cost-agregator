# Batch 24 — Budget & Financial Integrity (H9-H15, M6, M9, L5)

## Technical Plan (Advanced)
### Scope
- In:
  - Transactional safety, index/schema hardening, sensitive-data protection, network secret hygiene, and singleton state safety for:
    - `H9`, `H10`, `H11`, `H12`, `H13`, `H14`, `H15`, `M6`, `M9`, `L5`
  - Migrations/tests needed to safely roll out schema/security changes.
- Out:
  - No functional redesign of budgeting features.
  - No unrelated module refactors beyond required dependency wiring.
  - No destructive data migrations without recovery path.

### Complexity Assessment
- Estimated files touched: **12–22**
  - `data/repository/ReceiptRepository.kt`
  - `data/repository/ExpenseRepository.kt`
  - `data/repository/NotificationRepository.kt`
  - `data/database/entity/Expense.kt`
  - DAO + DB migration files (`AppDatabase` migration path)
  - `data/database/entity/BankConnection.kt`
  - `data/location/GeoapifyGeocodingService.kt`
  - `data/ai/provider/SmartReceiptAssistService.kt`
  - `data/database/entity/SpendingPersonalityProfileEntity.kt` + DAO
  - `di/NetworkModule.kt` and affected cloud services
- Risk level: **high**
- Cross-module impact: **yes** (data layer, security, migration, DI/network)

### Batch Plan
1. Batch name: **H9 — Make receipt->expense linking atomic via DB transaction**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
     - db transaction tests
   - objective:
     - Ensure expense creation and receipt-link updates commit/rollback as one unit.
   - risks:
     - Transaction scope could accidentally include non-DB side effects.
   - validation:
     - Forced failure mid-flow leaves no partial write artifacts.

   **Root Cause Analysis**
   - Multi-step DB writes are not wrapped transactionally in critical receipt conversion path.

   **Implementation Strategy**
   1. Wrap all related DAO mutations in `withTransaction`.
   2. Move side effects after commit.
   3. Add failure-injection test.

   **Dependencies**
   - Depends on DB access to transaction handle.

   **Risk Assessment**
   - High.

   **Verification Plan**
   - Integration test for mid-operation exception rollback.

   **Estimated Effort**
   - **Medium**

2. Batch name: **H10 — Make bulk merchant rename cross-table update atomic**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
   - objective:
     - Prevent partial rename propagation across expenses and pending review tables.
   - risks:
     - Large transaction may temporarily increase lock time.
   - validation:
     - Simulated failure yields full rollback of both tables.

   **Root Cause Analysis**
   - Bulk rename spans tables without transactional boundary.

   **Implementation Strategy**
   1. Execute all affected updates in one transaction.
   2. Keep deterministic ordering and idempotent behavior.

   **Dependencies**
   - Database transaction access.

   **Risk Assessment**
   - Medium-high.

   **Verification Plan**
   - Repository test with forced second-step failure.

   **Estimated Effort**
   - **Medium**

3. Batch name: **H11 — Ensure destructive/restore notification maintenance is transactional**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt`
   - objective:
     - Avoid partially wiped or partially restored multi-table states.
   - risks:
     - Long-running restore operations under transaction may hit contention.
   - validation:
     - Restore/delete operations are all-or-nothing.

   **Root Cause Analysis**
   - Multi-table maintenance operations not uniformly transacted.

   **Implementation Strategy**
   1. Wrap each maintenance operation in its own transaction boundary.
   2. Keep logging/telemetry outside transaction where possible.

   **Dependencies**
   - DB transaction handle.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Integration tests for partial-failure rollback behavior.

   **Estimated Effort**
   - **Medium**

4. Batch name: **H12 — Add standalone `expenses.date` index with safe migration**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt`
     - database migration definitions
   - objective:
     - Improve hot-path date sorting/range query performance.
   - risks:
     - Migration mistakes can block startup.
   - validation:
     - Schema verification and query plan checks show index usage.

   **Root Cause Analysis**
   - Frequent date queries lack dedicated index, causing expensive scans/sorts.

   **Implementation Strategy**
   1. Add entity-level index and explicit migration DDL.
   2. Keep migration idempotent (`IF NOT EXISTS`).
   3. Validate on upgrade test DB.

   **Dependencies**
   - Depends on migration version bump sequence.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Migration test + explain-plan sanity check.

   **Estimated Effort**
   - **Low-Medium**

5. Batch name: **H13 — Encrypt bank tokens at rest with migration-safe strategy**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt`
     - token storage/service adapters
     - migration scripts
   - objective:
     - Eliminate plaintext token persistence and migrate existing rows safely.
   - risks:
     - Migration/data-loss risk if crypto metadata handling is flawed.
   - validation:
     - Existing tokens remain usable post-migration; plaintext columns no longer exposed.

   **Root Cause Analysis**
   - Sensitive access/refresh tokens are stored as plain text columns.

   **Implementation Strategy**
   1. Introduce encrypted token format (ciphertext + metadata/alias).
   2. Implement staged migration with fallback/repair path.
   3. Add key-rotation-safe read/write adapter.

   **Dependencies**
   - Depends on secure key infra availability.

   **Risk Assessment**
   - High.
   - Mitigation: backup/recovery path, migration dry-run tests.

   **Verification Plan**
   - Migration integration tests on seeded plaintext DB.

   **Estimated Effort**
   - **High**

6. Batch name: **H14 — Remove API key exposure risk in geocoding logging/request patterns**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/location/GeoapifyGeocodingService.kt`
   - objective:
     - Ensure secrets never appear in logs/diagnostics.
   - risks:
     - Debuggability reduction if logs over-redacted.
   - validation:
     - Logs contain redacted metadata only.

   **Root Cause Analysis**
   - URL query key patterns and full URL logging can leak secrets.

   **Implementation Strategy**
   1. Stop logging full URLs; log correlation IDs + status classes.
   2. Prefer header auth where provider supports it; otherwise strict redaction.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Security-focused log assertion tests.

   **Estimated Effort**
   - **Low-Medium**

7. Batch name: **H15 — Remove mutable request-specific singleton state in SmartReceiptAssistService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
   - objective:
     - Guarantee concurrent safety and per-request metadata isolation.
   - risks:
     - Behavior drift in retry/metadata reporting if refactor is incomplete.
   - validation:
     - Concurrent requests never cross-contaminate attempt metadata.

   **Root Cause Analysis**
   - Singleton service historically vulnerable to request-scoped mutable fields.
   - Current implementation appears largely stateless but requires explicit guard tests.

   **Implementation Strategy**
   1. Keep all request metadata local to call scope/result object.
   2. Add concurrency tests with interleaved request completion.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Concurrency test harness for parallel `suggest()` invocations.

   **Estimated Effort**
   - **Medium**

8. Batch name: **M6 — Add index for active spending personality profile lookup**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/database/entity/SpendingPersonalityProfileEntity.kt`
     - profile DAO + migration
   - objective:
     - Speed up `isActive=1 LIMIT 1` hot query.
   - risks:
     - Minimal migration overhead.
   - validation:
     - Query plan uses new index.

   **Root Cause Analysis**
   - Active-profile query lacks dedicated supporting index.

   **Implementation Strategy**
   1. Add index on `isActive` and migration DDL.
   2. Verify with explain-plan.

   **Dependencies**
   - Migration sequencing.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Migration + DAO query benchmark sanity.

   **Estimated Effort**
   - **Low**

9. Batch name: **M9 — Standardize cloud client injection through DI-provided OkHttp client**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/di/NetworkModule.kt`
     - cloud provider services using custom clients
   - objective:
     - Ensure consistent timeout/interceptor/retry/security policy across cloud services.
   - risks:
     - Service behavior can change due to unified interceptors/timeouts.
   - validation:
     - All cloud services resolve the same qualified client; runtime behavior is consistent.

   **Root Cause Analysis**
   - Some services historically instantiated local clients, bypassing central policy.

   **Implementation Strategy**
   1. Enforce constructor injection with qualifiers.
   2. Remove ad-hoc client creation paths except explicit test constructors.
   3. Add wiring test to prevent regressions.

   **Dependencies**
   - Depends on DI module updates.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - DI graph compile checks + service smoke tests.

   **Estimated Effort**
   - **Medium**

10. Batch name: **L5 — Budget/Cashflow localization and copy hygiene pass**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingScreen.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarScreen.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt`
     - `app/src/main/res/values/strings.xml`
   - objective:
     - Remove remaining inline English copy in budget/cashflow/savings-adjacent screens.
   - risks:
     - Broad text diff can obscure functional review.
   - validation:
     - Hardcoded UI literals removed from targeted screens.

   **Root Cause Analysis**
   - Multiple budget/cashflow/savings UI sections still include inline labels and action text.

   **Implementation Strategy**
   1. Extract targeted literals with stable resource keys.
   2. Update semantic labels/content descriptions together.
   3. Keep this pass isolated from behavioral/security changes.

   **Dependencies**
   - Independent; should be separate PR chunk.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Grep + locale smoke checks.

   **Estimated Effort**
   - **Medium**

### Dependencies
- Recommended order: `H9/H10/H11` (transactional safety) → `H12/M6` (index/migrations) → `H13/H14/H15` (security/threading) → `M9` (DI unification) → `L5` (copy hygiene).
- `H13` should not ship without validated migration and rollback procedure.
- `M9` should follow security fixes to avoid masking service-specific issues during diagnosis.

### Rollback / Safety
- Separate schema migrations from business logic commits.
- Add pre-migration backup/export path for sensitive-token migration (`H13`).
- Keep each transaction fix isolated for selective rollback.
- Security logging changes (`H14`) should default to redaction; never re-enable full secret logs.

### Acceptance Criteria
- [ ] H9: Receipt-to-expense conversion is fully transactional with rollback on failure.
- [ ] H10: Bulk merchant rename updates all target tables atomically.
- [ ] H11: Notification destructive/restore maintenance operations are transactional.
- [ ] H12: `expenses.date` index exists and migration is verified.
- [ ] H13: Bank tokens are encrypted at rest with safe migration from plaintext.
- [ ] H14: Geocoding/network logs no longer expose secrets.
- [ ] H15: Smart receipt assist request metadata is concurrency-safe and isolated.
- [ ] M6: Active spending profile query uses dedicated index.
- [ ] M9: All cloud services use DI-managed qualified HTTP clients.
- [ ] L5: Targeted budget/cashflow/savings copy is resource-localized.
