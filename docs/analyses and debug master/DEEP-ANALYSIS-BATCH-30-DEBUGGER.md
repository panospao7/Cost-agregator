# Deep Analysis — Batch 30: Location, Geocoding & Workers (@debugger)

## Scope
- data/location/CompositeGeocodingService.kt
- data/location/GeoapifyGeocodingService.kt
- data/location/GooglePlacesGeocodingService.kt
- data/location/NominatimGeocodingService.kt
- data/location/PhotonGeocodingService.kt
- data/location/OverpassNearbyService.kt
- data/location/AndroidForegroundLocationProvider.kt
- data/location/LocationBackfillWorker.kt
- data/location/MerchantKeyBackfillWorker.kt
- data/location/internal/LogSanitizer.kt
- data/location/LocationModels.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | OverpassNearbyService.kt:77-98 | **HIGH** | Error Handling | Overpass retry helper closes and discards the final 429/5xx response, so callers see `NetworkError` instead of `RateLimited`/`HttpError` and cannot back off correctly. | 1. Overpass returns 429. 2. Retry helper exhausts retries. 3. Response body closed without reading. 4. Caller gets generic `NetworkError`. | Return the last HTTP response on the final attempt and only retry/close on earlier attempts. |
| 2 | OverpassNearbyService.kt:163-185 | **HIGH** | Logic Error | Overpass name matching removes all non-ASCII letters, so Greek merchant names normalize to empty strings and ranking degrades to distance-only. | 1. Search for Greek merchant "Σκλαβενίτης". 2. Name normalized to empty string. 3. All nearby POIs rank equally by distance. | Use Unicode-aware normalization (`\p{L}`/`\p{Nd}`) and align with existing Greek/Greeklish normalization. |
| 3 | MerchantKeyBackfillWorker.kt:44-65 | **HIGH** | Logic Error | Merchant-key backfill can loop forever when one row always fails to update because failures are swallowed and the same `merchantKey IS NULL` batch is fetched again immediately. | 1. One expense has unresolvable merchant. 2. Worker fetches batch, fails to update that row. 3. Next run fetches same batch. 4. Infinite loop. | Detect no-progress batches and stop with `Result.retry()` or persist a retry counter for failed rows. |
| 4 | LocationBackfillWorker.kt:81-107 | **HIGH** | Security/Privacy | Location backfill still logs raw merchant names, leaking transaction-derived PII to logcat/crash logs. | 1. Backfill processes expense with merchant "Starbucks Main St". 2. Log output: "Resolving 'Starbucks Main St'". 3. PII visible in logcat. | Remove raw merchant strings from logs or replace them with strong sanitized correlation tokens. |
| 5 | LogSanitizer.kt:3 | **HIGH** | Security | `anonymizeForLog()` is only `String.hashCode()` in hex, which is deterministic, unsalted, low-entropy, and brute-forceable for merchant/city/query values. | 1. Attacker sees hash "a1b2c3d4" in logs. 2. Brute-forces common merchant names. 3. Matches hash to "Starbucks". | Replace with keyed HMAC/SHA-256-based redaction or stop logging user-derived values. |
| 6 | AndroidForegroundLocationProvider.kt:21-23,44-48 | **MEDIUM** | Logic Error | Foreground location provider claims to fall back to last-known location, but it never does; it only requests a fresh current fix and has no explicit timeout/accuracy gate. | 1. GPS unavailable indoors. 2. Provider waits indefinitely for fresh fix. 3. No fallback to cached last-known location. | Add cached `lastLocation` fallback plus explicit timeout and accuracy validation. |
| 7 | CompositeGeocodingService.kt:77-80,348-370 | **MEDIUM** | Error Handling | Composite geocoder disables fallback when the primary provider throws an unexpected exception because `safeLookup()` maps it to `Unknown`, which is treated as non-transient. | 1. Primary provider throws unexpected exception. 2. `safeLookup()` maps to `Unknown`. 3. Fallback providers never tried. | Map thrown I/O/timeout failures to transient errors and/or allow fallback on provider-thrown `Unknown`. |
| 8 | NominatimGeocodingService.kt:81-103,252-287 | **MEDIUM** | Logic Error | Nominatim `searchMultiple()` ignores the caller-provided `limit` and always requests `NOMINATIM_MAX_RESULTS = 5`, reducing picker/composite result quality. | 1. Caller requests 10 results. 2. Nominatim returns max 5. 3. Picker shows fewer options than expected. | Thread the requested limit through URL/log-route builders with clamping. |
| 9 | LocationResolver.kt:233-240, LocationBackfillWorker.kt:88-110 | **HIGH** | Error Handling | Transient geocoder outages are collapsed to `Unresolved`, and the backfill worker burns retry budget on that result; after three temporary failures, an expense is permanently excluded from automatic backfill. | 1. Geocoder API temporarily down. 2. Backfill marks expense as `Unresolved`. 3. Retry count incremented. 4. After 3 failures, expense permanently excluded. | Preserve retryable vs terminal failure types through the resolver and only increment attempts for true terminal misses. |
| 10 | CompositeGeocodingService.kt:143-165, PhotonGeocodingService.kt:92, GeoapifyGeocodingService.kt:105, GooglePlacesGeocodingService.kt:122, NominatimGeocodingService.kt:229, OverpassNearbyService.kt:79 | **HIGH** | Resource Leak | Coroutine cancellation does not cancel underlying HTTP calls because providers use blocking `OkHttp.execute()`, so debounced/cancelled searches can still consume quota, radio, and rate-limit budget. | 1. User types in search bar, triggering geocoding. 2. User types again, cancelling previous search. 3. HTTP call still executes and consumes quota. | Use coroutine-aware OkHttp cancellation and cancel the `Call` when the coroutine is cancelled. |
| 11 | LocationResolver.kt:60-69, AndroidForegroundLocationProvider.kt:44-48 | **MEDIUM** | Performance | Device location is fetched before cache/correction checks, so batch resolution can repeatedly trigger expensive fresh fixes even when cache hits would have resolved immediately. | 1. Backfill processes 100 expenses. 2. Each triggers fresh location fix. 3. Cache could have resolved 80 of them. | Defer location acquisition until bias is actually needed, memoize it per worker run, and prefer cached location for batch flows. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | OverpassNearbyService ↔ Merchant Normalization | **HIGH** | Greek Name Destruction | Overpass name matching strips all non-ASCII, destroying Greek merchant names and degrading ranking to distance-only. | Use Unicode-aware normalization. |
| C2 | LocationBackfillWorker ↔ LocationResolver | **HIGH** | Transient Failure Permanence | Transient geocoder outages are treated as permanent failures, burning retry budget and permanently excluding expenses from backfill. | Preserve retryable vs terminal failure types. |
| C3 | All Geocoding Providers ↔ Coroutine Cancellation | **HIGH** | Uncancellable HTTP Calls | Blocking `OkHttp.execute()` prevents coroutine cancellation from stopping in-flight requests, wasting quota and battery. | Use coroutine-aware OkHttp and cancel `Call` on coroutine cancellation. |
| C4 | LogSanitizer ↔ All Location Services | **HIGH** | Weak Anonymization | `hashCode()`-based anonymization is brute-forceable for common merchant/city names, leaking PII through logs. | Use keyed HMAC/SHA-256. |
| C5 | LocationResolver ↔ AndroidForegroundLocationProvider | **MEDIUM** | Premature Location Fetch | Location is fetched before checking cache, causing unnecessary GPS fixes for cacheable resolutions. | Defer location acquisition and memoize per worker run. |

## Summary
- **Total issues: 16** (11 file-level + 5 cross-component)
- **Critical: 0**, **High: 8**, **Medium: 4**, **Low: 0**
- **Files with issues: 9/11**

## Key Patterns

### 1. Greek/Unicode Text Destruction
Multiple location services strip non-ASCII characters, destroying Greek merchant names and degrading matching quality to distance-only ranking.

### 2. Transient Failure Misclassification
Transient geocoder outages are treated as permanent failures, burning retry budget and permanently excluding expenses from backfill.

### 3. Uncancellable HTTP Calls
All geocoding providers use blocking `OkHttp.execute()`, preventing coroutine cancellation from stopping in-flight requests.

### 4. Weak Log Anonymization
`hashCode()`-based anonymization is deterministic and brute-forceable, providing false security for logged merchant/city names.

### 5. Infinite Worker Loops
Merchant-key backfill can loop forever when one row always fails, with no progress detection or retry limiting.
