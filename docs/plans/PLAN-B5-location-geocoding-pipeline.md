## Technical Plan

### Scope
- In: all **HIGH** rows under `### B.5: Location/Geocoding Pipeline` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, limited to Overpass retry semantics, Unicode-aware name ranking, merchant-key backfill liveness, location/log privacy hardening, retryable geocoder failure propagation, cancellable provider HTTP calls, branch-safe merchant-location caching, and heatmap negative-total handling.
- Out: all **MEDIUM/LOW** B.5 rows, Room schema/entity/migration work, `AppConfig.Location.NOMINATIM_USER_AGENT` hardcoded-email cleanup, `AndroidForegroundLocationProvider` last-known fallback, Nominatim `limit` contract drift, eager device-location fetching, grid-bucketing fixes, price-protection issues, and any opportunistic map/UI redesign.
- Assumptions / unknowns:
  - `B.4` must be locally committed before B.5 execution begins; this plan is valid now but execution is gated by that local commit.
  - The registry currently contains stale duplicate rows: the `SpendingMapViewModel` heatmap filter appears already resolved by `A.10`, and the merchant-location global-key encoding appears already standardized by `B.4`; both must be **live-file verified** before docs are updated.
  - One B.4-adjacent behavior appears to have doc/code drift: current `MerchantLocationDao.getByNormalizedName()` still falls back to area rows when no global row exists. Treat the live code/tests as the execution source of truth and do not trust the old resolved marker blindly.

### Files
- create: `app/src/main/java/com/yourname/expensetracker/data/location/internal/CancellableHttpCall.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/internal/LogSanitizer.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/PhotonGeocodingService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/GeoapifyGeocodingService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/GooglePlacesGeocodingService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/NominatimGeocodingService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/OverpassNearbyService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/location/GeocodingCancellationTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/location/OverpassNearbyServiceTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/location/internal/LogSanitizerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/location/GeocodingRetryHttpSemanticsTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/location/LocationBackfillWorkerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/location/LocationResolverStressTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngineStressTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModelStressTest.kt`
- modify: `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDaoTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-30.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-42.md`
- create: `docs/reviews/REVIEW-B5.md`

### 1. Objective & Blast Radius
- **Core issue:** B.5 still has multiple HIGH-severity failures where geocoding transport/cancellation, merchant-location cache semantics, retry-budget handling, and privacy redaction drift away from the intended location pipeline behavior. The fix must close only the B.5 **HIGH** issues and avoid widening into medium/low cleanup.
- **Blast radius:** location provider services, Overpass ranking, worker liveness, location/log privacy, merchant-location cache lookup semantics, map manual-resolution flow, heatmap weighting, DAO tests, and registry/final-verification documentation.
- **Primary packages:**
  - `app/src/main/java/com/yourname/expensetracker/data/location/`
  - `app/src/main/java/com/yourname/expensetracker/data/location/internal/`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/`
  - `app/src/main/java/com/yourname/expensetracker/data/database/dao/`
  - `app/src/main/java/com/yourname/expensetracker/domain/location/`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/map/`

> [!WARNING]
> - Do **not** touch B.5 MEDIUM/LOW rows in this plan.
> - Do **not** change Room entities, schema versions, migrations, indices, or column names.
> - Do **not** refactor `CompositeGeocodingService` or `LocationSearchPicker` unless the new cancellable-call helper proves an actual cancellation propagation gap after the provider fixes land.
> - Do **not** reopen the A.10 heatmap transaction-type fix unless the live audit proves regression; if a separate place-insights issue is found, record it instead of widening B.5.

### 2. The Single Source of Truth
- **Transport truth:** all location-network calls must go through a single coroutine-aware OkHttp bridge that binds coroutine cancellation to `Call.cancel()`. Raw provider-side `execute()` calls are no longer allowed in `data/location/`.
- **Log redaction truth:** `data/location/internal/LogSanitizer.kt` is the only approved way to log user-derived location strings. No local `hashCode()` helpers, no raw merchant names, no duplicate anonymizers.
- **Retry truth:** transient geocoder/provider failures must leave `LocationResolver` as an explicit retryable signal; only true terminal misses/manual-selection outcomes may become `Unresolved`/attempt-budget consumption.
- **Cache-scope truth:** `MerchantLocationRepository.getCachedLocation()` means **true global cache only**. Area-scoped rows must be returned only from the explicit area lookup path.
- **Write-scope truth:** GPS-biased and name-only geocode wins are branch-local unless explicitly promoted; they must be stored under an area key derived from the resolved/bias location, not the default `"global"` key.
- **Heatmap truth:** `SpendingHeatmapEngine` weights positive spending mass only. Non-positive values must not produce `ln(1 + negative)` or inverted/NaN weights.

> [!WARNING]
> - Preferred narrow fix for retry semantics: introduce a dedicated retryable-resolution signal inside the location pipeline instead of widening repository APIs.
> - Preferred narrow fix for log hardening: keep the public helper shape as `String.anonymizeForLog()`; do not spread new DI/plumbing across unrelated packages unless absolutely necessary.
> - If a fix seems to require changing entities or adding persistent retry columns, stop and split it; that is outside B.5 HIGH scope.

### Implementation Steps
1. Land the shared cancellable HTTP helper and migrate provider call sites in two small batches.
2. Fix worker liveness and logging/privacy issues without changing scheduling contracts.
3. Enforce strict merchant-location global cache semantics and branch-safe cache writes.
4. Preserve transient geocoder failures as retryable outcomes instead of burning backfill budget.
5. Stabilize heatmap weighting, then close documentation with live-audited upstream-resolution markers for A.10/B.4 rows.

### 3. File-by-File Execution Checklist (micro-batches)

#### Batch 1 — Shared cancellable HTTP foundation
- Files:
  - create: `app/src/main/java/com/yourname/expensetracker/data/location/internal/CancellableHttpCall.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/PhotonGeocodingService.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/GeoapifyGeocodingService.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/GooglePlacesGeocodingService.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/location/GeocodingCancellationTest.kt`
- Checklist:
  - [ ] Add one internal helper that wraps OkHttp calls with `suspendCancellableCoroutine`, cancels the underlying `Call` on coroutine cancellation, and returns an open `Response` to the caller.
  - [ ] Replace raw `client.newCall(request).execute()` usage in Photon/Geoapify/Google Places with the helper while preserving existing retry/backoff/status-code mapping.
  - [ ] Ensure cancellation rethrows `CancellationException`; it must **not** be converted to `NetworkError` or `Unknown`.
  - [ ] `GeocodingCancellationTest.kt`: prove canceling the coroutine cancels the underlying call for the shared helper/provider path.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.GeocodingCancellationTest"`
- Rollback / stop rule:
  - If this helper forces changes to non-location packages or broad OkHttp DI refactoring, stop and split.

#### Batch 2 — Nominatim + Overpass transport semantics
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/NominatimGeocodingService.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/OverpassNearbyService.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/location/GeocodingRetryHttpSemanticsTest.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/location/OverpassNearbyServiceTest.kt`
- Checklist:
  - [ ] Move Nominatim and Overpass off raw blocking `execute()` onto the shared cancellable helper without breaking Nominatim rate limiting or Overpass POST request behavior.
  - [ ] `OverpassNearbyService.kt`: return the final `429`/`5xx` response on the last retry attempt instead of closing it and collapsing to generic `NetworkError`.
  - [ ] `OverpassNearbyService.kt`: switch name ranking to Unicode-aware normalization so Greek merchant names do not normalize to empty strings.
  - [ ] `GeocodingRetryHttpSemanticsTest.kt`: keep/extend the existing provider retry tests after the transport migration.
  - [ ] `OverpassNearbyServiceTest.kt`: add focused regressions for (a) final-429 semantics and (b) Greek-name ranking.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.GeocodingRetryHttpSemanticsTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.OverpassNearbyServiceTest"`
- Rollback / stop rule:
  - Do **not** fold in Nominatim `limit` fixes, fallback-logic changes, or safeLookup medium-scope cleanup here.

#### Batch 3 — Merchant-key backfill worker liveness
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt`
- Checklist:
  - [ ] Detect no-progress batches / repeated failed IDs within the same run instead of immediately re-querying the same permanently failing rows forever.
  - [ ] Return `Result.retry()` when the worker makes no progress, while preserving already-successful row updates from the same run.
  - [ ] Keep the one-time scheduling contract and repository API unchanged.
  - [ ] `MerchantKeyBackfillWorkerTest.kt`: add a regression where one row fails repeatedly and the worker exits with retry rather than spinning.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.MerchantKeyBackfillWorkerTest"`
- Rollback / stop rule:
  - Do **not** add new DB columns, retry tables, or WorkManager rescheduling logic in this batch.

#### Batch 4 — Centralized strong log redaction
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/internal/LogSanitizer.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/location/internal/LogSanitizerTest.kt`
- Checklist:
  - [ ] Replace `hashCode()`-hex redaction with a stronger centralized token (salted/keyed SHA-256 family; no deterministic 32-bit fallback).
  - [ ] Preserve the public extension shape `String.anonymizeForLog()` so existing provider call sites stay narrow.
  - [ ] `LogSanitizerTest.kt`: prove output is non-raw, stable within-process for the same input, and materially different for distinct inputs.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.internal.LogSanitizerTest"`
- Rollback / stop rule:
  - If keystore-backed injection would widen the blast radius beyond the location package, use a narrow process-local salt strategy instead of introducing broad new DI.

#### Batch 5 — Strict global cache lookup semantics
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt`
  - modify: `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDaoTest.kt`
- Checklist:
  - [ ] Make the repository’s global cache lookup path return only true `areaKey = "global"` rows; no arbitrary area-scoped fallback is allowed through `getCachedLocation()`.
  - [ ] Add/rename the minimum DAO helper(s) needed for strict global lookup while preserving the explicit area-scoped lookup path and hit-count behavior.
  - [ ] Reverse the existing test expectation that “no global row → any area row is acceptable”; the correct result is `null`.
  - [ ] Do **not** touch TTL-on-read behavior here; that medium B.32 row remains out of scope.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.dao.MerchantLocationDaoTest`
- Rollback / stop rule:
  - Do **not** alter entity defaults, migrations, or existing `areaKey` encoding in this batch.

#### Batch 6 — Branch-safe geocode cache writes
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/location/LocationResolverStressTest.kt`
- Checklist:
  - [ ] `LocationResolver.kt`: GPS-biased and name-only geocode wins must save under an area key derived from the resolved/bias coordinates, not the default global key.
  - [ ] Preserve correction hits, history-biased area-cache precedence, and existing public `resolve(...)` signature.
  - [ ] Add regressions proving geocode success uses non-global `saveLocation(..., areaKey)` for branch-local resolutions.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.location.LocationResolverStressTest"`
- Rollback / stop rule:
  - Do **not** reorder correction/cache/device-location phases for the medium battery/latency row.

#### Batch 7 — Resolver retryable failures + shared sanitizer adoption
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/location/LocationResolverStressTest.kt`
- Checklist:
  - [ ] Remove the resolver’s private `anonymizeForLog()` helper and route all redaction through `data/location/internal/LogSanitizer.kt`.
  - [ ] Preserve transient provider failures as an explicit retryable signal from `LocationResolver` instead of collapsing them to `null`/`Unresolved`.
  - [ ] Keep terminal `NoResults`/manual-selection behavior intact; only transient classes (`RateLimited`, `ServiceDown`, `NetworkError`, `Timeout`, `HttpError(5xx)`) should short-circuit as retryable.
  - [ ] `LocationResolverStressTest.kt`: add a regression proving a transient geocoder failure surfaces as retryable instead of `Unresolved`.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.location.LocationResolverStressTest"`
- Rollback / stop rule:
  - Do **not** change other geocoder fallback rules or medium-scope provider exception mapping while landing this retry signal.

#### Batch 8 — Backfill/manual-resolve handling for retryable failures
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/location/LocationBackfillWorkerTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModelStressTest.kt`
- Checklist:
  - [ ] `LocationBackfillWorker.kt`: never log raw merchant names; log only expense ID + shared sanitized token.
  - [ ] `LocationBackfillWorker.kt`: do **not** increment `backfillAttempts` for retryable/internal resolver failures; instead surface `Result.retry()` for the run while still allowing already-resolved rows to persist.
  - [ ] `SpendingMapViewModel.kt`: catch the retryable resolver signal in manual resolution flows and show a temporary-failure snackbar instead of crashing or pretending the merchant is permanently unresolved.
  - [ ] `LocationBackfillWorkerTest.kt`: assert retryable failures no longer consume attempt budget.
  - [ ] `SpendingMapViewModelStressTest.kt`: add/adjust a regression for manual resolve temporary failure messaging.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.LocationBackfillWorkerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.map.SpendingMapViewModelStressTest"`
- Rollback / stop rule:
  - Do **not** rewrite the full map screen UX or worker scheduling contract here.

#### Batch 9 — Heatmap normalization hardening + upstream-resolution audit lock
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngine.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngineStressTest.kt`
- Checklist:
  - [ ] Ignore non-positive `LocatedExpense.amount` values before cluster accumulation/log-normalization so `ln(1 + totalSpend)` never receives a negative input.
  - [ ] Preserve the current purchase-only heatmap contract; do **not** change clustering math or widen into place-insight filtering.
  - [ ] Update stress tests so negative-only inputs return empty/safe output and mixed positive/negative inputs weight only positive spend.
  - [ ] Audit only: verify `SpendingMapViewModel.kt` still builds `heatmapExpenses` from `transactionType.toDomain().isSpending`; if true, close the registry row as an A.10 documentation update only.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.location.SpendingHeatmapEngineStressTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.map.SpendingMapViewModelStressTest"`
- Rollback / stop rule:
  - If the audit reveals a broader `LocationInsightsEngine` or map-input contract issue not named in the registry bullet, record it and defer; do not widen B.5 opportunistically.

### 4. Verification Plan
- **Static verification after each batch:**
  - Re-read every modified file.
  - Confirm imports/signatures remain valid.
  - Grep for forbidden leftovers:
    - `hashCode().toUInt().toString(16)` under `data/location/` and `domain/location/`
    - `private fun String.anonymizeForLog` under `domain/location/LocationResolver.kt`
    - `newCall(request).execute()` under `data/location/`
    - raw merchant-name log patterns in `LocationBackfillWorker.kt`
- **Serialized Gradle lane (orchestrator-owned; one pipeline at a time):**
  1. `./gradlew.bat :app:compileDebugKotlin`
  2. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.GeocodingCancellationTest"`
  3. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.GeocodingRetryHttpSemanticsTest"`
  4. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.OverpassNearbyServiceTest"`
  5. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.MerchantKeyBackfillWorkerTest"`
  6. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.internal.LogSanitizerTest"`
  7. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.location.LocationBackfillWorkerTest"`
  8. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.location.LocationResolverStressTest"`
  9. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.map.SpendingMapViewModelStressTest"`
  10. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.location.SpendingHeatmapEngineStressTest"`
  11. If Android test lane is available: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.dao.MerchantLocationDaoTest`
- **Reviewer focus points:**
  - No remaining provider-side blocking `execute()` calls in `data/location/`.
  - No remaining raw merchant-name logs or local anonymizer helpers.
  - `getCachedLocation()` no longer returns area rows.
  - Retryable failures no longer increment `backfillAttempts`.
  - Heatmap negative inputs cannot produce NaN/inverted weights.

### 5. Documentation & Registry Updates
- **Registry update target:** `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` under `### B.5: Location/Geocoding Pipeline`.
- **Mark `[RESOLVED BY B.5]` after code + tests + review PASS:**
  - `OverpassNearbyService.executeWithRetry()` closes every 429/5xx response including the last one...
  - `Overpass name ranking strips everything outside [a-z0-9]`...
  - `MerchantKeyBackfillWorker` per-row update failures swallowed...
  - `LocationBackfillWorker` logs raw merchant names...
  - `LogSanitizer.anonymizeForLog()` is just `String.hashCode()`...
  - `LocationResolver` defines its own private `anonymizeForLog()`...
  - `LocationResolver.geocode()` collapses every geocoder failure to `null`...
  - `All geocoding providers use blocking OkHttp.execute()`...
  - `LocationResolver.saveLocation()` saves GPS-biased and name-only resolutions under global area key...
  - `SpendingHeatmapEngine` sums raw `amount` values with `ln(1 + totalSpend)`...
  - `LocationResolver global cache fallback returns arbitrary area-scoped entry when no global entry exists`...
  - the duplicate `Merchant location global-cache fallback returns arbitrary area-scoped entry...` row should either be collapsed into the B.5 row or annotated with the same B.5 resolution marker.
- **Audit-only upstream-resolution markers (no production churn unless the live audit fails):**
  - Mark `Map pipeline consumes getLocatedExpensesFlow() without filtering transaction type...` as `[RESOLVED BY A.10]` only if `SpendingMapViewModel.kt` still routes heatmap inputs through `transactionType.toDomain().isSpending`.
  - Mark `Merchant location global-key encoding inconsistency — "global" vs "<normalized>|global"` as `[RESOLVED BY B.4 — Batch 5]` only if `MerchantLocation.kt`, `MerchantLocationCorrection.kt`, and `MerchantLocationRepository.kt` still use plain `"global"`.
- **Exact final-verification docs to update after review PASS:**
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-30.md`
    - update Verified Issues `#1-5`
    - update Missed Issue `#1`
    - update Cross-Component Issues `#1-2`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-42.md`
    - update Verified Issue `#3`
    - update Verified Issue `#14`
    - update Cross-Component Issue `#4`
    - preserve the existing `[RESOLVED BY A.10]` note on Cross-Component Issue `#5` if the audit still passes
- **Review artifact:** after all code batches pass review, create/update `docs/reviews/REVIEW-B5.md` with the final PASS report before the commit.

### Risks
- `LocationResolver.kt` is a hotspot touched by multiple batches; each batch must reread the live file before editing to avoid stomping prior fixes.
- The DAO behavior and existing docs disagree on the global-cache fallback contract; documentation must follow tested live behavior, not stale registry text.
- A bad cancellable-call helper can leak/close responses incorrectly and silently break retry semantics; batch 1 and 2 must keep response ownership explicit.
- Android DAO verification may require a serialized instrumentation lane; if unavailable locally, reviewer/orchestrator must record the limitation instead of falsely claiming it passed.
- If the log-sanitizer hardening depends on wide new secret-injection plumbing, the blast radius can grow quickly; keep the fix narrow and location-local.

### Acceptance Criteria
- [ ] No location provider in `data/location/` still relies on raw `OkHttp.execute()` without coroutine-linked cancellation.
- [ ] Overpass keeps terminal `429`/`5xx` semantics on the last retry attempt and ranks Greek merchant names meaningfully.
- [ ] `MerchantKeyBackfillWorker` cannot spin indefinitely on a permanently failing row.
- [ ] No raw merchant strings or `hashCode()`-based redaction remain in the B.5 location pipeline logs.
- [ ] `LocationResolver` no longer owns a private anonymizer helper and no longer collapses transient provider failures into terminal unresolved outcomes.
- [ ] `LocationBackfillWorker` consumes retry budget only for terminal misses/manual-selection outcomes.
- [ ] Manual map resolution degrades gracefully on transient lookup failures instead of crashing or misclassifying them as permanent misses.
- [ ] Global cache lookup returns only true global rows; branch-local geocode writes are area-scoped.
- [ ] Heatmap normalization is safe for negative/non-positive totals and cannot produce NaN/inverted weights.
- [ ] The stale A.10 and B.4 rows in the registry are closed by live-code audit, not by assumption.
- [ ] `MASTER-ISSUE-REGISTRY.md`, `FINAL-VERIFICATION-BATCH-30.md`, `FINAL-VERIFICATION-BATCH-42.md`, and `REVIEW-B5.md` are updated in the same closeout.
