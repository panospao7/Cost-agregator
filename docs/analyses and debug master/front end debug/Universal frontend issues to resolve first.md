Short answer: **use a hybrid approach**.

Do **not** fix everything globally in one giant PR. But also do **not** go slice-by-slice without first creating a few universal frontend contracts, because you will repeat the same bugs 14 times.

## Universal frontend issues to resolve first

These should be fixed once, then reused by every slice.

### 1. Money/currency display contract

This is the biggest universal issue.

Problems repeated everywhere:
- fallback `"EUR"`
- `collectAsState(initial = "")`
- raw `Double` formatted as money
- mixed-currency totals raw-summed

Create one shared UI model:

```kotlin
data class MoneyDisplayUi(
    val amount: Double,
    val currency: String,
    val formatted: String,
    val isPartial: Boolean = false,
    val warning: UiText? = null
)
```

Rule:

> No frontend screen may format financial amounts without explicit currency.

---

### 2. Mutation state/idempotency pattern

Repeated issue:
- dialogs close before save result
- double taps can create duplicate writes
- failures only shown as snackbars

Create shared pattern:

```kotlin
data class MutationState(
    val operation: String? = null,
    val targetId: Long? = null,
    val isRunning: Boolean = false,
    val error: UiText? = null
)
```

Rule:

> Dialogs/sheets close only after success event.

---

### 3. Loading/error/empty/degraded UI contract

Every slice should expose typed states, not empty lists pretending to be success.

Use:

```kotlin
sealed interface LoadableUiState<out T> {
    data object Loading : LoadableUiState<Nothing>
    data class Data<T>(val value: T) : LoadableUiState<T>
    data class Empty(val reason: UiText) : LoadableUiState<Nothing>
    data class Error(val message: UiText) : LoadableUiState<Nothing>
}
```

---

### 4. Privacy-blocked UI

Use `PrivacyBlockedCard` everywhere for:
- cloud AI blocked
- GPS blocked
- geocoding blocked
- export blocked
- backup blocked
- raw OCR/debug blocked

Rule:

> Privacy denial is not a snackbar-only error.

---

### 5. Route/content/component split

Universal Compose structure:

```text
FeatureRoute.kt       // ViewModel/Hilt/state collection/events
FeatureContent.kt     // pure state + callbacks
FeatureCards.kt
FeatureDialogs.kt
```

Do this per slice, but use the same pattern.

---

### 6. No composable should call domain/network services

Repeated issue:
- location picker calls geocoding
- review/edit screens receive services
- debug/export actions too direct

Rule:

> Composables render state and emit callbacks only.

---

### 7. Time/date policy

Replace raw:
- `Calendar.getInstance()`
- `LocalDate.now()`
- `System.currentTimeMillis()`

with injected `TimeProvider` / `ZoneId`.

This affects Home, Analytics, Budget, Search, Tax, Challenges, Warranty, etc.

---

### 8. Sensitive diagnostics/logging policy

Universal rule:

> No release UI/logs should expose raw merchant, query, address, OCR, notification, or financial totals.

Debug details should be gated.

---

## Recommended approach

### Phase 1 — Universal frontend foundation PRs

Do these first:

1. `MoneyDisplayUi` + currency formatter policy.
2. `MutationState` + event pattern.
3. `LoadableUiState` / degraded state pattern.
4. `PrivacyBlockedCard` typed API.
5. `UiText` / safe error message pattern.
6. Route/content/component template.
7. Test utilities: fixed clock, fake currency, fake privacy gate.

### Phase 2 — Slice-by-slice fixes

Then go slice by slice.

Order I recommend:

1. Slice 1 navigation
2. Slice 2 shared UI
3. Slice 3 privacy/security
4. Slice 5 transactions/add expense
5. Slice 6 review
6. Slice 7 receipt scan
7. Slice 4 home dashboard
8. Slice 8 budget/cashflow
9. Slice 9 analytics
10. Slice 10 map/location
11. Slice 11 AI
12. Slices 12–14

Reason: transactions/review/receipt are ledger-write paths. Fix them before dashboards/analytics that read from them.

## Final recommendation

Use **slice-by-slice implementation**, but create **universal contracts first**.

Best agent instruction:

> First add shared frontend contracts for money, mutation state, privacy blocked UI, load/error states, route/content splitting, and safe diagnostics. Then migrate each slice incrementally using those contracts. Do not do a big-bang rewrite.