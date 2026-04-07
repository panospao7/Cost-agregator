# Final Verification — Batch 30: Location, Geocoding & Workers

## Scope
- `com/yourname/expensetracker/data/location/CompositeGeocodingService.kt`
- `com/yourname/expensetracker/data/location/GeoapifyGeocodingService.kt`
- `com/yourname/expensetracker/data/location/GooglePlacesGeocodingService.kt`
- `com/yourname/expensetracker/data/location/NominatimGeocodingService.kt`
- `com/yourname/expensetracker/data/location/PhotonGeocodingService.kt`
- `com/yourname/expensetracker/data/location/OverpassNearbyService.kt`
- `com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt`
- `com/yourname/expensetracker/data/location/LocationBackfillWorker.kt`
- `com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt`
- `com/yourname/expensetracker/data/location/internal/LogSanitizer.kt`
- `com/yourname/expensetracker/domain/location/LocationModels.kt`
- `com/yourname/expensetracker/domain/location/LocationResolver.kt`
- `com/yourname/expensetracker/ui/components/LocationSearchPicker.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/location/OverpassNearbyService.kt:70-98` | High | Error handling / retry | `executeWithRetry()` closes every `429`/`5xx` response, including the last one, then throws a generic `IOException`. `findNearby()` therefore converts terminal throttle/server responses into `NetworkError`, so callers lose `RateLimited`/`HttpError` semantics. | B | CONFIRMED | On the final attempt, return the last HTTP response instead of closing it; only retry/close earlier attempts. |
| 2 | `com/yourname/expensetracker/data/location/OverpassNearbyService.kt:162-185` | High | Locale / matching | Overpass name ranking strips everything outside `[a-z0-9]`, so Greek merchant names normalize to empty strings and similarity collapses to `0.0`; ranking then degrades to distance-only. | B | CONFIRMED | Use Unicode-aware normalization (for example `[^\p{L}\p{Nd}]`) and align it with the existing Greek/Greeklish merchant normalization path. |
| 3 | `com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt:44-65` | High | Worker liveness | Per-row update failures are swallowed, but failed rows remain `merchantKey IS NULL`. The outer `while (!isStopped)` immediately re-queries the same batch, so a permanently failing row can keep the worker spinning until WorkManager stops it. | B | CONFIRMED | Detect no-progress batches, track failed IDs per run, and exit with `Result.retry()` or a persisted retry budget instead of tight reprocessing. |
| 4 | `com/yourname/expensetracker/data/location/LocationBackfillWorker.kt:75-109` | High | Privacy / logging | The worker logs raw merchant names on resolver exception, `NeedsUserSelection`, and `Unresolved` paths, exposing transaction-derived identifiers in logcat/crash logs. | B | CONFIRMED | Remove merchant names from logs or replace them with a strong sanitized correlation token plus the expense ID. |
| 5 | `com/yourname/expensetracker/data/location/internal/LogSanitizer.kt:3` | High | Security / privacy | `anonymizeForLog()` is just `String.hashCode()` in hex. It is deterministic, unsalted, 32-bit, collision-prone, and practical to brute-force for common merchant/city/query values. | B | CONFIRMED | Replace it with a centralized keyed HMAC/SHA-256 redaction function, or stop logging user-derived values entirely. |
| 6 | `com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt:21-23,41-48` | Medium | Contract / performance | The class claims to fall back to last-known location, but `getLastKnownLocation()` only calls `getCurrentLocation()` and never reads cached `lastLocation`. It also leaves timeout/accuracy behavior entirely to Play Services. | B | CONFIRMED | Check cached `lastLocation` first or as fallback, and add an explicit freshness/accuracy policy for batch flows. |
| 7 | `com/yourname/expensetracker/data/location/CompositeGeocodingService.kt:66-80,360-370` | Medium | Fallback logic | `safeLookup()` maps unexpected provider exceptions to `GeocodingError.Unknown`, and `search()` only cascades on transient errors. A thrown primary-provider exception therefore disables the documented fallback chain. | B | CONFIRMED | Map common thrown exceptions to transient error types and/or allow fallback on provider-thrown `Unknown` failures. |
| 8 | `com/yourname/expensetracker/data/location/NominatimGeocodingService.kt:81-103,252-314` | Medium | API contract | `searchMultiple()` accepts `limit`, but `buildUrl()`/`buildSafeLogRoute()` always send `NOMINATIM_MAX_RESULTS`, so callers requesting more candidates never receive them. | B | CONFIRMED | Thread the requested `limit` through URL and safe-log builders with sane clamping. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/location/LocationResolver.kt:261-262` | High | Security / privacy | `LocationResolver` defines its own private `anonymizeForLog()` using the same weak `hashCode()` scheme instead of the shared sanitizer. Even if `LogSanitizer` is fixed later, resolver logs remain brute-forceable and the privacy fix stays inconsistent. | Delete the local helper and route all log redaction through a single hardened sanitizer implementation. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| — | — | — | None. All reported findings were reproducible in the current codebase; duplicate reviewer/debugger entries were merged rather than treated as separate bugs. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `GeocodingService -> LocationResolver -> LocationBackfillWorker` | High | Retry semantics / data loss | `LocationResolver.geocode()` collapses every geocoder failure to `null`, so transient outages/rate limits become `LocationResolutionResult.Unresolved`. `LocationBackfillWorker` then increments `backfillAttempts`, and after three transient failures the expense is excluded from future automatic backfill without any terminal “no match” decision. | `com/yourname/expensetracker/domain/location/LocationResolver.kt`, `com/yourname/expensetracker/data/location/LocationBackfillWorker.kt` | Preserve retryable vs terminal failure types through the resolver and only consume retry budget for true terminal misses/manual-selection outcomes. |
| 2 | `Coroutine cancellation / worker stop -> geocoding providers` | High | Cancellation / quota waste | Picker debounce cancellation and coroutine cancellation do not cancel underlying HTTP calls because all providers use blocking `OkHttp.execute()`. Cancelled searches and stopped tasks can therefore keep spending quota, radio time, and rate-limit budget until each request completes or times out. | `com/yourname/expensetracker/ui/components/LocationSearchPicker.kt`, `com/yourname/expensetracker/data/location/CompositeGeocodingService.kt`, `com/yourname/expensetracker/data/location/PhotonGeocodingService.kt`, `com/yourname/expensetracker/data/location/GeoapifyGeocodingService.kt`, `com/yourname/expensetracker/data/location/GooglePlacesGeocodingService.kt`, `com/yourname/expensetracker/data/location/NominatimGeocodingService.kt`, `com/yourname/expensetracker/data/location/OverpassNearbyService.kt` | Switch to coroutine-aware OkHttp integration (`Call.await()` / `suspendCancellableCoroutine`) and cancel the underlying `Call` from the coroutine cancellation handler. |
| 3 | `AndroidForegroundLocationProvider -> LocationResolver -> LocationBackfillWorker` | Medium | Battery / latency | `LocationResolver` fetches device location before correction/cache checks, and the provider favors a fresh current fix rather than a cheap cached one. In backfill runs this can repeat per expense, multiplying latency and battery cost even when cache hits would have resolved the merchant immediately. | `com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt`, `com/yourname/expensetracker/domain/location/LocationResolver.kt`, `com/yourname/expensetracker/data/location/LocationBackfillWorker.kt` | Defer device-location acquisition until a bias is actually needed, memoize it per worker run, and prefer cached last-known location for batch resolution. |

## Summary
- Total verified issues: 11
- Confirmed: 11 (Critical: 0, High: 7, Medium: 4, Low: 0)
- False positives: 0
- Missed issues found: 1
- Files affected: 12/13

## Key Patterns
- Error classification is still inconsistent across retries and downstream consumers: provider-specific failures are frequently collapsed into generic states before the pipeline can make correct retry/backoff decisions.
- Privacy hardening is incomplete in two ways: some logs still emit raw merchant names, and the “sanitized” paths rely on weak unhashed or duplicated `hashCode()`-based redaction.
- Worker resilience is uneven: one worker can spin forever on a non-updatable row, while another burns finite retry budget on transient upstream failures.
- Cancellation and location acquisition are both more expensive than intended: HTTP calls are not actually cancellable, and fresh device location is fetched eagerly before cheaper cache/correction paths are exhausted.
