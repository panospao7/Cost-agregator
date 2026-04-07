# Deep Analysis — Batch 30: Location, Geocoding & Workers (@reviewer)

## Scope
- `app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/GeoapifyGeocodingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/GooglePlacesGeocodingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/NominatimGeocodingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/PhotonGeocodingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/OverpassNearbyService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/internal/LogSanitizer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/location/LocationModels.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/location/OverpassNearbyService.kt:77-98` | HIGH | Error handling / retry | `executeWithRetry()` closes and discards every `429`/`5xx` response, including the final attempt, then throws a generic `IOException`. The caller therefore reports `NetworkError` instead of `RateLimited`/`HttpError`, so callers cannot back off correctly when Overpass is throttling or down. | On the final attempt, return the last HTTP response instead of closing it; only retry/close on non-final attempts. Preserve `429` and `5xx` as structured `GeocodingError` values. |
| 2 | `data/location/OverpassNearbyService.kt:163-185` | HIGH | Locale / matching bug | Name ranking strips everything outside `[a-z0-9]`. Greek merchant names therefore normalize to the empty string, making `nameSimilarity()` return `0.0` for both the query and candidates. In the app’s primary locale this collapses ranking to distance-only and can surface the wrong POIs. | Normalize with Unicode-aware classes (for example `[^\p{L}\p{Nd}]`) and reuse the same Greek/Greeklish normalization path used elsewhere in merchant matching. |
| 3 | `data/location/MerchantKeyBackfillWorker.kt:44-65` | HIGH | Worker liveness | Per-row update failures are swallowed, but failed rows remain `merchantKey IS NULL`. The outer `while (!isStopped)` immediately fetches the same rows again, so one permanently failing row can make the worker spin forever until WorkManager kills it. | Track per-run failures/progress, stop when a batch makes no progress, and return `Result.retry()` or persist a retry counter for failed rows. |
| 4 | `data/location/LocationBackfillWorker.kt:81-107` | HIGH | Privacy / logging | The worker logs raw merchant names in three paths (`Resolver threw`, `NeedsUserSelection`, `Unresolved`). These are transaction-derived identifiers and leak user activity into logcat/crash logs. | Replace raw merchant strings with a strong sanitized token (or omit them entirely) and keep only expense IDs / hashed correlation IDs. |
| 5 | `data/location/internal/LogSanitizer.kt:3` | HIGH | Security / privacy | `anonymizeForLog()` uses plain `String.hashCode()` rendered as hex. This is deterministic, unsalted, 32-bit, collision-prone, and easy to brute-force for low-entropy merchant names, cities, and coordinate strings, so it is not robust anonymization. | Replace with a keyed HMAC/SHA-256-based sanitizer (or stop logging user-derived values altogether). |
| 6 | `data/location/AndroidForegroundLocationProvider.kt:21-23,44-48` | MEDIUM | Contract / performance | The class documentation promises fallback to last-known location “if a fresh fix cannot be obtained quickly”, but the implementation only calls `getCurrentLocation()` and never falls back to `lastLocation`. There is also no explicit timeout/accuracy gate, so poor GPS conditions can stall resolution longer than intended. | Read cached `lastLocation` first (or as fallback), add an explicit timeout, and validate accuracy before returning a fresh fix. |
| 7 | `data/location/CompositeGeocodingService.kt:77-80,348-370` | MEDIUM | Fallback logic | `search()` only cascades when the primary error is classified as transient, but `safeLookup()` converts unexpected provider exceptions into `GeocodingError.Unknown`, which is treated as non-transient. A thrown primary-provider bug therefore disables the advertised fallback chain completely. | Map common thrown exceptions (`IOException`, timeout exceptions, parse exceptions) to structured transient errors and/or treat provider-thrown `Unknown` failures as fallback-eligible. |
| 8 | `data/location/NominatimGeocodingService.kt:81-103,252-287` | MEDIUM | API contract | `searchMultiple()` accepts a `limit`, but `buildUrl()` always sends `AppConfig.Location.NOMINATIM_MAX_RESULTS` (`5`). Callers asking for more candidates never get them, which weakens the picker and the composite merge. | Thread the requested `limit` through `searchMultiple()`, `buildUrl()`, and `buildSafeLogRoute()` (with sane clamping). |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `GeocodingService -> LocationResolver -> LocationBackfillWorker` | HIGH | Retry semantics / data loss | Geocoding providers return structured transient errors, but `LocationResolver` collapses failures to `null`, and `LocationBackfillWorker` increments `backfillAttempts` on the final `Unresolved` result. After three transient outages/rate-limit events, an expense is excluded from future automatic backfill even though no terminal “no match” decision was made. | Preserve retryable vs terminal failure types through the resolver, and only burn `backfillAttempts` for true `NoResults`/manual-selection outcomes. |
| 2 | `LocationSearchPicker -> CompositeGeocodingService -> provider services` | HIGH | Cancellation / quota waste | The picker debounces by cancelling coroutines, and `CompositeGeocodingService` comments claim cancellation propagates, but all provider clients use blocking `OkHttp.execute()` calls without coroutine-aware cancellation. Rapid typing or screen exit can therefore leave multiple HTTP requests running to completion, wasting quota, battery, and rate-limit budget. | Switch to coroutine-aware OkHttp integration (`Call.await()` / `suspendCancellableCoroutine`) and cancel the underlying `Call` when the coroutine is cancelled. |
| 3 | `AndroidForegroundLocationProvider -> LocationResolver -> LocationBackfillWorker` | MEDIUM | Battery / latency | `LocationResolver` requests device location before cache/correction checks, and the provider tries to obtain a fresh current fix rather than cheap cached state. During backfill this can repeat per expense, multiplying GPS latency and power use even when a cache hit would have resolved the merchant immediately. | Defer device-location acquisition until a bias is actually needed, memoize it per worker run, and prefer cached last-known location for batch flows. |

## Summary
- Total issues: 11
- Critical: 0, High: 7, Medium: 4, Low: 0
- Files with issues: 7/11

## Key Patterns
- Error classifications are inconsistent across the pipeline: several services define structured `GeocodingError` states, but retry helpers and downstream consumers sometimes collapse them back to generic failures.
- Privacy hardening is incomplete: some code still logs raw merchant names, and the shared sanitizer is too weak to provide meaningful protection.
- Worker resilience is uneven: one worker can spin forever on a permanently failing row, while another can permanently exhaust retry budget on transient upstream failures.
- Location acquisition is more expensive than necessary because fresh device fixes are requested eagerly and without a true cached fallback path.
