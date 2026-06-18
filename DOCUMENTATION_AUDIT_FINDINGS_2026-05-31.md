# Documentation Audit Findings — 2026-05-31

**Scope:** Cross-check architectural docs against current codebase (HEAD through commit 08432119)  
**Pipelines Analyzed:** 5-12 (Currency, Budget, Backup, Privacy, Workers, Bank, Email, Export)  
**Commits Reviewed:** 40+ recent commits with major refactoring  
**Status:** 126+ NEW issues discovered in deep audit; docs lag behind code changes

---

## Executive Summary

The architectural documentation is **significantly outdated** relative to the current codebase. While the reference maps (backend-domain-map.md, backend-data-map.md) were refreshed on 5/30/2026, they do not reflect:

1. **126+ NEW issues** discovered in deep code audit (5/31/2026)
2. **Major architectural changes** in pipelines 5-12 over the last 40+ commits
3. **New failure modes and edge cases** not documented
4. **Evolved patterns** (e.g., explicit unavailable state handling, fail-closed semantics)
5. **Integration gaps** between pipelines that are now apparent

**Recommendation:** Prioritize updating docs for pipelines 5-12 with current architectural state, known issues, and integration patterns.

---

## Documentation Status by File

### ✅ Recently Updated (5/30/2026)
- `docs/reference/backend-domain-map.md` — Domain layer (but missing P5-P12 changes)
- `docs/reference/backend-data-map.md` — Data layer (but missing P5-P12 changes)
- `docs/reference/backend-di-infrastructure-map.md` — DI setup (current)
- `docs/reference/BACKEND-MAP-INDEX.md` — Index (but marked "STALE" in header)

### ⚠️ Partially Outdated (5/8/2026 or earlier)
- `docs/reference/clean-architecture-violations-report.md` — Pre-P5 refactoring
- `docs/reference/domain-quick-reference.md` — Missing new types (MoneyAggregate, PeriodRange, etc.)
- `docs/reference/UI_INTEGRATION_SUMMARY.md` — Pre-P5 integration patterns
- `docs/reference/BACKEND-DEPENDENCIES.md` — Missing new dependency chains

### 🔴 Severely Outdated (Pre-Pipeline Refactoring)
- `docs/architecture/ENGINE_INTERACTION_MAP.md` — Pre-P5/P6 engine coordination
- `docs/architecture/DEPENDENCY_MAP.md` — Missing new modules and patterns
- `docs/architecture/hilt-bindings-map.md` — Pre-P5 DI structure

---

## Specific Outdated Sections by Pipeline

### Pipeline 5 — Currency/Dashboard/Analytics

**Docs Say:**
- `backend-domain-map.md` line 91: "BudgetForecastingEngine.getSpentAmount() now routes through AnalyticsCurrencyNormalizer"
- `backend-data-map.md`: MultiCurrencyRepository uses "dual rate-basis strategy"

**Code Actually Does:**
- ✅ AnalyticsCurrencyNormalizer exists and is used
- ✅ MoneyNormalizationEngine is the canonical normalizer
- ✅ Per-expense TRANSACTION_DATE conversion implemented
- ❌ **14 NEW issues discovered** (P5-NEW-001 through P5-NEW-014):
  - `previousMonthAggregate` always null (dead feature)
  - Division by zero risk in `projectedTotal`
  - Deposit filter includes not-mine items
  - `getAverageForPeriodType(DAY)` wrong denominator
  - SynthesisEngine sums planned expenses across currencies
  - And 9 more...

**Missing from Docs:**
- Explicit unavailable state handling via `MoneyAggregateResult`
- Fail-closed semantics on home-currency unavailability
- Stale-rate detection logic (7-day threshold)
- PURCHASE-only vs ALL_TYPES aggregation distinction
- Dashboard synthesis engine architecture

**Action Items:**
1. Document `MoneyAggregateResult` as primary aggregation return type
2. Add section on currency quality indicators (staleRateCount, conversionFailures)
3. Document PURCHASE-only historical APIs
4. Add known issues section with P5-NEW-001 through P5-NEW-014

---

### Pipeline 6 — Budget/Forecasting/Cashflow

**Docs Say:**
- `backend-domain-map.md` line 85-90: Budget engines produce "budget trends, remaining runway, scenario-based forecast results"
- Forecast data quality is "ignored by SynthesisEngine"

**Code Actually Does:**
- ✅ BudgetForecastingEngine uses AnalyticsCurrencyNormalizer
- ✅ CashFlowCalculator projects recurring occurrences in-memory
- ✅ FinancialStressForecastEngine runs Monte Carlo simulation
- ❌ **16 NEW issues discovered** (P6-NEW-001 through P6-NEW-016):
  - CancellationException swallowed in multiple paths
  - Unbounded rollover loop (O(N) queries for daily budgets)
  - Stress expandDetectedPatterns double-counts
  - DST-unsafe day arithmetic
  - Hardcoded currency-specific risk thresholds
  - And 11 more...

**Missing from Docs:**
- Occurrence projection with in-memory merging (no row materialization)
- Fail-closed result types (BudgetForecastResult)
- Durable diagnostics for forecast generation
- Write barrier enforcement on forecast writes
- Schema upgrade 141→142 implications
- Stress forecast limitations (not real account-balance forecast)

**Action Items:**
1. Document occurrence projection architecture
2. Add section on result types and unavailable states
3. Document stress forecast limitations and assumptions
4. Add known issues section with P6-NEW-001 through P6-NEW-016
5. Document schema migration path (141→142)

---

### Pipeline 7 — Backup/Restore

**Docs Say:**
- `backend-data-map.md` line 28-32: "RestoreMaintenanceMode manages 10 maintenance modes"
- "Pauses all 7 background workers via WorkerLeaseRegistry"

**Code Actually Does:**
- ✅ RestoreMaintenanceMode exists with 10 modes
- ✅ WorkerLeaseRegistry coordination works
- ❌ **6 NEW issues discovered** (P7-NEW-001 through P7-NEW-006):
  - Encrypted export never exits maintenance mode on success (P0 critical)
  - Privacy gate denial / WAL failure leak maintenance mode
  - RestoreJournal appendEvent read-modify-write race
  - CostbackupBundle.extract() leaks FileInputStream
  - And 2 more...

**Missing from Docs:**
- Fail-closed recovery mode (CRITICAL_RECOVERY_REQUIRED)
- RestoreJournal 8-state crash recovery mechanism
- BackupVerifier 56-entity 3-tier verification
- CostbackupBundle ZIP format with AES-256-GCM encryption
- Maintenance mode state machine transitions
- Known failure modes and recovery paths

**Action Items:**
1. Document maintenance mode state machine with all 10 states
2. Add section on RestoreJournal crash recovery
3. Document CostbackupBundle format and encryption
4. Add known issues section with P7-NEW-001 through P7-NEW-006
5. Document fail-closed recovery semantics

---

### Pipeline 8 — Privacy/AI/Redaction

**Docs Say:**
- `backend-domain-map.md` line 36: "PrivacyCapability (20-value enum), PrivacyGate (interface)"
- "RawStorageMode enum controls write-time sanitization"

**Code Actually Does:**
- ✅ PrivacyGate interface exists with implementations
- ✅ RawStorageMode enum controls sanitization
- ❌ **8 NEW issues discovered** (P8-NEW-001 through P8-NEW-008):
  - updateSettings() TOCTOU race
  - DataRetentionWorker loop no checkpoint for 5 targets
  - MERCHANT_LINE_REGEX over-matches
  - CloudPiiSanitizer missing patterns
  - And 4 more...

**Missing from Docs:**
- PrivacySettings-authoritative redaction pattern
- Typed privacy-denied surface for AiServiceResult
- Fail-closed test gates
- Composite gate pattern with fail-closed semantics
- HMAC-based deduplication for sensitive fields
- Privacy audit logging architecture
- Known gaps in retention worker scope

**Action Items:**
1. Document PrivacySettings-authoritative redaction pattern
2. Add section on composite gate pattern
3. Document HMAC-based deduplication
4. Add known issues section with P8-NEW-001 through P8-NEW-008
5. Document retention worker scope and gaps

---

### Pipeline 9 — Workers/Background Jobs

**Docs Say:**
- `backend-domain-map.md` line 34-35: "WorkerRegistry (single source-of-truth for 7 background workers)"
- "WorkerExecutionGuard guards worker execution with privacy checks"

**Code Actually Does:**
- ✅ WorkerRegistry exists with 7 workers
- ✅ WorkerExecutionGuard enforces multi-layer checks
- ✅ WorkerLeaseRegistry coordinates maintenance operations
- ✅ All 12 old issues FIXED (per PIPELINE_9_CONSOLIDATED_ISSUES.md)
- ⚠️ **No NEW issues discovered** — Pipeline 9 is in good state

**Missing from Docs:**
- Lease-based coordination for maintenance operations
- Guard pattern with multi-layer checks (privacy, database, maintenance, permissions)
- Durable run logging via BackgroundJobRunDao
- Worker spec versioning and constraints
- Backoff policy and repeat interval configuration

**Action Items:**
1. Document lease-based coordination pattern
2. Add section on guard pattern with multi-layer checks
3. Document worker spec versioning
4. Add durable run logging architecture

---

### Pipeline 10 — Bank Integration

**Docs Say:**
- `backend-data-map.md` line 40-41: "BankConnectionDao manages bank connections with OAuth token storage"
- "BankStatementImportRunDao tracks import runs with status, item counts"

**Code Actually Does:**
- ✅ BankConnectionDao exists with OAuth token encryption
- ✅ BankStatementImportRunDao tracks import runs
- ✅ STRICT_EXTERNAL_ID bank imports implemented
- ✅ Cancellation rethrow fixed
- ⚠️ **4 open issues** (P10-CURRENT-006/007/008/018 per tracker)

**Missing from Docs:**
- OAuth token encryption with version tracking
- Import run state machine with comprehensive metrics
- Stale run detection and recovery
- STRICT_EXTERNAL_ID deduplication strategy
- Bank statement lifecycle processor integration

**Action Items:**
1. Document OAuth token encryption
2. Add section on import run state machine
3. Document STRICT_EXTERNAL_ID strategy
4. Add known issues section

---

### Pipeline 11 — Email Receipt Ingestion

**Docs Say:**
- `backend-data-map.md` line 43-44: "EmailReceiptDao manages email receipt sources with deduplication"
- "Supports insertOrIgnore for non-destructive inserts"

**Code Actually Does:**
- ✅ EmailReceiptDao exists with multi-key deduplication
- ✅ Privacy-aware field storage with hash-based lookups
- ⚠️ **5 open issues** (P11-CURRENT-001/020 and others per tracker)
  - Fingerprint too coarse; other failures ignored
  - Barrier/conflict partially addressed

**Missing from Docs:**
- Multi-key deduplication strategy (messageId, hash, fingerprint)
- Privacy-aware field storage architecture
- Provider-based organization
- Email receipt data collision handling
- Home-currency hoisting from transaction

**Action Items:**
1. Document multi-key deduplication strategy
2. Add section on privacy-aware field storage
3. Document email receipt data collision handling
4. Add known issues section

---

### Pipeline 12 — Import/Export/Accounting

**Docs Say:**
- `backend-data-map.md` line 45-46: "ExportDataRepository uses deterministic keyset pagination"
- "CsvCellSanitizer RFC-4180-safe CSV encoder"

**Code Actually Does:**
- ✅ ExportDataRepository uses keyset pagination (date ASC, id ASC)
- ✅ CsvCellSanitizer implements RFC-4180 encoding
- ✅ Formula injection neutralization (prepends "'" for =+-@)
- ⚠️ **7 open issues** (P12-REG-01/NEW-01/CURRENT-015/003/020 and others)

**Missing from Docs:**
- Deterministic keyset pagination limitations (not point-in-time snapshot)
- DatabaseReadBarrier with EXPORT_OR_BACKUP_SNAPSHOT_READ policy
- Multi-format export support (IIF, CSV, PDF)
- RFC-4180 CSV encoding with formula injection protection
- Privacy gates guarding export capabilities
- Known limitations of keyset pagination

**Action Items:**
1. Document keyset pagination limitations
2. Add section on DatabaseReadBarrier
3. Document multi-format export support
4. Add known issues section

---

## Cross-Pipeline Integration Gaps

**Docs Miss:**
1. **Shared Dependencies** — AnalyticsCurrencyNormalizer used by P5, P6, Analytics
2. **Write Barrier Pattern** — DatabaseWriteBarrier guards all writes across pipelines
3. **Read Barrier Pattern** — DatabaseReadBarrier guards export/backup reads
4. **Maintenance Mode Coordination** — RestoreMaintenanceMode pauses/resumes workers
5. **Privacy Gate Enforcement** — PrivacyGate guards capabilities across Privacy, Export, Worker pipelines
6. **Fail-Closed Semantics** — Explicit unavailable state handling (MoneyAggregateResult, BudgetForecastResult)
7. **Durable Diagnostics** — DiagnosticEventWriter used across pipelines for observability

**Action Items:**
1. Create "Cross-Pipeline Patterns" section in architecture docs
2. Document shared dependency chains
3. Document barrier patterns (write, read, maintenance)
4. Document fail-closed semantics across pipelines

---

## Universal Issues (U-PR1 through U-PR8)

**Docs Say:** Nothing — universal issues not documented in reference maps

**Code Actually Has:** 8 universal PRs addressing cross-cutting concerns:
- U-PR1: Cancellation safety (CancellationException handling)
- U-PR2: TOCTOU race conditions
- U-PR3: Money/currency type safety
- U-PR4: Barrier maintenance (write/read barriers)
- U-PR5: Privacy contract enforcement
- U-PR6: Worker guard improvements
- U-PR7: Time provider injection
- U-PR8: Side-effect coordination

**Missing from Docs:**
- Universal issue tracker and implementation plans
- Cross-cutting concern patterns
- Shared architectural contracts

**Action Items:**
1. Create "Universal Issues" section in architecture docs
2. Document each U-PR with scope and implementation strategy
3. Link universal issues to pipeline-specific issues

---

## Known Issues Not in Reference Docs

**Total NEW Issues Discovered (5/31/2026):** 126 across all 12 pipelines

**By Pipeline:**
- P1: 16 NEW issues (CancellationException, source-link I/O, filter bugs, race conditions)
- P2: 9 NEW issues (TOCTOU races, non-atomic operations)
- P3: 6 NEW issues (CancellationException, double attachReceipt, privacy leaks)
- P4: 7 NEW issues (CancellationException, race conditions, notification ID collision)
- P5: 14 NEW issues (dead features, division by zero, currency mismatches)
- P6: 16 NEW issues (CancellationException, unbounded loops, DST issues)
- P7: 6 NEW issues (maintenance mode leaks, race conditions, resource leaks)
- P8: 8 NEW issues (TOCTOU race, checkpoint gaps, regex issues)
- P9: 0 NEW issues (pipeline in good state)
- P10: 4 open issues (from tracker)
- P11: 5 open issues (from tracker)
- P12: 7 open issues (from tracker)

**Action Items:**
1. Create "Known Issues by Pipeline" section in each pipeline doc
2. Link to consolidated issue registries
3. Prioritize P0/P1 issues for documentation

---

## Documentation Update Priority

### 🔴 Critical (Update Immediately)

1. **Pipeline 5 — Currency/Dashboard/Analytics**
   - 14 NEW issues, including P0 (previousMonthAggregate dead feature)
   - Missing MoneyAggregate documentation
   - Missing currency quality indicators

2. **Pipeline 6 — Budget/Forecasting/Cashflow**
   - 16 NEW issues, including P1 (CancellationException, unbounded loops)
   - Missing occurrence projection architecture
   - Missing stress forecast limitations

3. **Pipeline 7 — Backup/Restore**
   - 6 NEW issues, including P0 (maintenance mode leak)
   - Missing fail-closed recovery semantics
   - Missing RestoreJournal crash recovery

### ⚠️ High (Update This Week)

4. **Pipeline 8 — Privacy/AI/Redaction**
   - 8 NEW issues, including P1 (TOCTOU race)
   - Missing PrivacySettings-authoritative redaction pattern
   - Missing retention worker scope gaps

5. **Pipeline 12 — Import/Export/Accounting**
   - 7 open issues
   - Missing keyset pagination limitations
   - Missing DatabaseReadBarrier documentation

6. **Universal Issues (U-PR1 through U-PR8)**
   - 8 cross-cutting concerns not documented
   - Missing shared architectural contracts

### 📋 Medium (Update Next Week)

7. **Pipeline 10 — Bank Integration**
   - 4 open issues
   - Missing OAuth token encryption details
   - Missing import run state machine

8. **Pipeline 11 — Email Receipt Ingestion**
   - 5 open issues
   - Missing multi-key deduplication strategy
   - Missing privacy-aware field storage

9. **Cross-Pipeline Integration**
   - Missing shared dependency chains
   - Missing barrier patterns documentation
   - Missing fail-closed semantics documentation

---

## Specific File Updates Needed

### `docs/reference/backend-domain-map.md`

**Lines to Update:**
- Line 91: Add explicit unavailable state handling via MoneyAggregateResult
- Line 85-90: Add occurrence projection architecture for P6
- Add new section: "Fail-Closed Semantics" (MoneyAggregateResult, BudgetForecastResult)
- Add new section: "Known Issues by Pipeline" (link to consolidated registries)

**New Sections to Add:**
- "Universal Issues (U-PR1 through U-PR8)"
- "Cross-Pipeline Patterns" (shared dependencies, barriers, fail-closed semantics)
- "Pipeline 5-12 Recent Changes" (summary of 40+ commits)

### `docs/reference/backend-data-map.md`

**Lines to Update:**
- Line 28-32: Add RestoreJournal crash recovery details
- Line 40-41: Add OAuth token encryption details
- Line 43-44: Add multi-key deduplication strategy for email receipts
- Line 45-46: Add keyset pagination limitations

**New Sections to Add:**
- "Barrier Patterns" (DatabaseWriteBarrier, DatabaseReadBarrier)
- "Maintenance Mode State Machine" (all 10 states)
- "Known Issues by Pipeline" (link to consolidated registries)

### `docs/reference/BACKEND-MAP-INDEX.md`

**Update Header:**
- Remove "STALE" marker or update to "Last validated: 2026-05-31"
- Add note about 126 NEW issues discovered

**New Sections to Add:**
- "Universal Issues (U-PR1 through U-PR8)"
- "Known Issues Summary" (by pipeline and severity)
- "Documentation Gaps" (what's missing)

### `docs/architecture/ENGINE_INTERACTION_MAP.md`

**Status:** Severely outdated (pre-P5/P6 refactoring)

**Needs Complete Rewrite:**
- Add AnalyticsCurrencyNormalizer as central hub
- Add MoneyNormalizationEngine
- Add occurrence projection architecture
- Add fail-closed result types
- Add barrier pattern interactions

### `docs/architecture/DEPENDENCY_MAP.md`

**Status:** Missing new modules and patterns

**Needs Updates:**
- Add new DI modules (if any added in P5-P12)
- Add new dependency chains
- Add barrier pattern dependencies
- Add privacy gate dependencies

---

## Recommendations

### Immediate Actions (Today)

1. **Create DOCUMENTATION_AUDIT_FINDINGS.md** (this file) ✅
2. **Update BACKEND-MAP-INDEX.md header** — Remove "STALE" marker
3. **Add "Known Issues" section** to each pipeline doc with links to consolidated registries
4. **Create "Universal Issues" section** in architecture docs

### This Week

5. **Update backend-domain-map.md** with P5-P12 changes
6. **Update backend-data-map.md** with barrier patterns and maintenance mode
7. **Create "Cross-Pipeline Patterns" section** in architecture docs
8. **Document fail-closed semantics** across all pipelines

### Next Week

9. **Rewrite ENGINE_INTERACTION_MAP.md** with current architecture
10. **Update DEPENDENCY_MAP.md** with new modules and chains
11. **Create "Pipeline 5-12 Architecture Guide"** with detailed walkthroughs
12. **Link all docs** to consolidated issue registries

### Ongoing

13. **Keep docs in sync** with code changes (update on each pipeline commit)
14. **Maintain consolidated issue registries** (PIPELINE_{1-12}_CONSOLIDATED_ISSUES.md)
15. **Track universal issues** (U-PR1 through U-PR8) in architecture docs

---

## Conclusion

The architectural documentation is **significantly outdated** relative to the current codebase. While recent reference maps (5/30/2026) provide a good foundation, they do not reflect:

- 126+ NEW issues discovered in deep audit
- Major architectural changes in pipelines 5-12
- New failure modes and edge cases
- Evolved patterns (fail-closed semantics, explicit unavailable states)
- Integration gaps between pipelines

**Priority:** Update docs for pipelines 5-12 with current architectural state, known issues, and integration patterns. Start with P5, P6, P7 (critical issues) and work through P8, P12, and universal issues.

**Estimated Effort:** 40-60 hours to fully update all documentation to current state.

---

**Generated:** 2026-05-31  
**Audit Scope:** Pipelines 5-12, 40+ recent commits, 126+ NEW issues  
**Status:** Ready for implementation
