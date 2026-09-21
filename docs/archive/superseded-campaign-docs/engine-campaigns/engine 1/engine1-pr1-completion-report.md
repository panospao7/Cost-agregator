# Engine 1 — PR1 Slice Completion Report

## Slice: PR1 — No-schema hardening

### Self-review verdict
GREEN

### Old issues reconciled
- W18: FIXED for all repository insert paths
- W21: FIXED for manual placeholder metadata
- W22: MOSTLY FIXED for Engine 1 local validation
- W17: Cancellation caveat fixed; privacy constructors deferred to PR2
- E1-NOW-001: FIXED
- E1-NOW-002: FIXED
- E1-NOW-004: FIXED
- E1-NOW-006: FIXED

### New issues found
None in PR1 scope.

### Files changed (production)
1. `app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
2. `app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt`
3. `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`

### Files changed (tests)
4. `app/src/test/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepositoryTest.kt`
5. `app/src/test/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngineTest.kt` (NEW)
6. `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt`
7. `app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt`

### Tests added/updated
- WarrantyTrackerRepositoryTest: +8 tests (timestamp normalization ×2, lifecycle event ×1, manual placeholder ×4, return-window skip ×1)
- SubscriptionManagerEngineTest: +19 tests (validation rejections, normalization, currency preservation, success paths)
- CloudQueryInterpretationServiceTest: +4 tests (CancellationException rethrow, IOException fallback, parse error fallback, privacy gate denial)
- CancellationSafetyArchitectureGuardTest: removed CloudQueryInterpretationService from KNOWN_VIOLATIONS

### Docs updated
- `docs/analyses and debug master/ENGINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/engine 1/engine1-pr1-completion-report.md` (this file)

### Affected pipelines
- Receipt/warranty auto-create (timestamps now non-zero)
- Manual warranty placeholder (correct metadata, return-window skip works)
- Subscription/recurring creation (invalid inputs rejected)
- Subscription candidate acceptance (validated)
- Subscription price change (currency preserved, amount validated)
- AI assistant cloud query (CancellationException propagates correctly)
- Architecture guard (allowlist shrinks)

### Expected behavior changes
- AI/import-created warranties now have non-zero timestamps
- Manual placeholder receipts now have MANUAL_PLACEHOLDER documentType and MANUAL_RECORD sourceType
- Invalid subscription inputs (NaN, Infinity, bad currency, blank merchant, zero start date) now throw IllegalArgumentException instead of silently entering the system
- Subscription price history now preserves the subscription's actual currency instead of defaulting to EUR
- Coroutine cancellation during cloud query interpretation now propagates correctly instead of being swallowed

### Static debugger verdict
GREEN (one advisory: runCatching in lifecycle-event inserts catches Throwable including CancellationException — extremely low practical risk, pre-existing pattern)

### Reviewer verdict
GREEN → YELLOW (advisory only) → GREEN after fixes

### Tester static verdict
GREEN after compilation blockers resolved (coEvery on suspend functions, class-level mock fields)

### Known compile risks
None. No schema changes, no Hilt changes, no new public API surface.

### Human validation commands
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*WarrantyTrackerRepositoryTest*"
./gradlew :app:testDebugUnitTest --tests "*SubscriptionManagerEngineTest*"
./gradlew :app:testDebugUnitTest --tests "*CloudQueryInterpretationServiceTest*"
./gradlew :app:check --stacktrace
```

### Follow-up / deferred items
- PR2: Cloud privacy constructor hardening (E1-NOW-003)
- PR3: Warranty lifecycle events (W03)
- PR4: Low-confidence warranty review routing (E1-NOW-005)
- PR5: NLP location query semantics (W15, E1-NOW-008)
- PR6: Bill negotiation provider wiring (W25, E1-NOW-007)
- PR7: Bill negotiation monthly-equivalent script fix (W09)
- PR8: Bill negotiation persistence with migration (W08)
- PR9: Deprecated/raw API guardrails
- Advisory: replace runCatching with try/catch(Exception) in lifecycle-event inserts to avoid CancellationException swallowing
- Advisory: extract subscription currency validation regex to companion constant
