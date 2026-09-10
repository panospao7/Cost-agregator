# Cancellation Safety Policy

Last updated: 2026-07-01  
PR: PR 1 — Baseline and Policies  
MIT: MIT-034  
Status: **APPROVED — awaiting implementation in PR 2+**

---

## 1. Purpose

Define the mandatory patterns for `CancellationException` handling in all Kotlin coroutine paths (suspend functions, workers, receivers, coroutine scopes). This policy prevents false success, false failure, and hung coroutines caused by swallowed cancellation.

---

## 2. Non-Negotiable Rule

> **Any `catch` block that catches `Exception` or `Throwable` in a suspend function, worker, receiver, or coroutine scope MUST rethrow `CancellationException` before handling the error.**

---

## 3. Allowed Patterns

### 3.1 Pattern A — CE-first catch (PREFERRED)

```kotlin
suspend fun doWork() {
    try {
        // ... risky operation ...
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // handle non-cancellation errors
    }
}
```

### 3.2 Pattern B — CE rethrow in broad catch

```kotlin
suspend fun doWork() {
    try {
        // ... risky operation ...
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // handle non-cancellation errors
    }
}
```

### 3.3 Pattern C — Cancellation-safe runCatching equivalent

```kotlin
suspend fun <T> runSuspendCatchingCancellable(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

### 3.4 Pattern D — Worker guard integration

Workers that use `WorkerExecutionGuard.executeWithGuard { }` are already covered — the guard rethrows CE. Workers with custom try/catch must follow Patterns A or B.

### 3.5 Pattern E — UI ViewModel launch blocks

```kotlin
viewModelScope.launch {
    try {
        // ... operation ...
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // show error, log sanitized diagnostic
    }
}
```

---

## 4. Forbidden Patterns

### 4.1 ❌ Broad catch without CE rethrow

```kotlin
// FORBIDDEN in suspend/worker/receiver paths:
suspend fun doWork() {
    try {
        riskyOperation()
    } catch (e: Exception) {
        logError(e)
        return failureResult
    }
}
```

**Why forbidden:** `CancellationException` extends `Exception`. A broad catch converts cancellation into a "failure," preventing structured concurrency from propagating cancellation.

### 4.2 ❌ runCatching in suspend paths without CE guard

```kotlin
// FORBIDDEN in suspend paths without additional CE handling:
suspend fun doWork(): Result<Data> {
    return runCatching { fetchData() }  // CE swallowed!
}
```

**Why forbidden:** `runCatching` catches all `Throwable` including `CancellationException` and wraps it in `Result.failure`. Downstream code sees a failure instead of cancellation.

**Exception:** `runCatching` is acceptable in:
- Non-suspend functions
- UI helpers (e.g., color parsing, enum `valueOf`)
- Pure data transformations that cannot throw `CancellationException`
- Immediately followed by CE rethrow pattern

### 4.3 ❌ onFailure without CE preservation

```kotlin
// FORBIDDEN in coroutine context:
scope.launch {
    flow.collect { data ->
        // ...
    }
}.invokeOnCompletion { throwable ->
    if (throwable != null) {
        emitErrorState(throwable)  // CE suppressed!
    }
}
```

**Why forbidden:** `invokeOnCompletion` can receive `CancellationException` and treat it as a generic error.

### 4.4 ❌ Returning Result.success after cancellation

```kotlin
// FORBIDDEN:
suspend fun doWork(): Result<Data> {
    return try {
        Result.success(fetchData())
    } catch (e: CancellationException) {
        Result.success(defaultData)  // Never return success after cancellation!
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

---

## 5. Allowlist — Safe Exceptions

Rare cases where broad catch without CE rethrow is acceptable:

| Context | Reason | Required annotation/comment |
|---------|--------|---------------------------|
| Non-suspend `BroadcastReceiver.onReceive()` | Cannot receive CE | `// Non-suspend receiver — no CE possible` |
| Non-suspend `Service.onCreate()` | Cannot receive CE | `// Non-suspend lifecycle — no CE possible` |
| File I/O in non-suspend private helpers | Cannot receive CE | `// Non-suspend helper — no CE possible` |
| `SQLiteDatabase.openDatabase()` in crash recovery | Non-suspend startup path | `// Non-suspend startup recovery — no CE possible` |
| JSON serialize/deserialize in non-suspend fun | Cannot receive CE | `// Non-suspend serialization — no CE possible` |

---

## 6. Static Enforcement

### 6.1 Existing Guard

`CancellationSafetyArchitectureGuardTest` — scans source files: any broad `catch (e: Exception)` or `catch (t: Throwable)` in suspend functions must contain `CancellationException` reference in the catch body. **Currently PASSING** in CI (146 guards across 38 files).

### 6.2 Planned Enhancement (PR 2)

A `CancellationSafe` shared helper class with:
- `runSuspendCatchingCancellable(block)` — cancellation-safe Result wrapper
- `mapFailureRethrowCancellation(result)` — map Result to domain result with CE rethrow
- `catchAndSanitizeRethrowCancellation(block)` — sanitize+log non-CE, rethrow CE

### 6.3 Planned Detekt Rule (PR 2)

Custom detekt rule `SuspendFunctionBroadCatch`:
- Flags `catch (e: Exception)` in `suspend fun` without CE rethrow
- Flags `runCatching` in `suspend fun`
- Exempts patterns with explicit CE-first catch

---

## 7. Audit Criteria

After implementation (PR 2+), every `catch (e: Exception)` or `catch (t: Throwable)` in:

- Workers (`worker/`, `service/reminder/`, `service/receiptmatching/`, etc.)
- Receivers (`receiver/`)
- Suspend coordinators (`lifecycle/`)
- Suspend repository methods (`repository/`)
- Service coroutine scopes (`service/`)

Must be classified as:
1. **CE-guarded** — has explicit CE rethrow (Pattern A or B)
2. **Non-suspend** — in a non-suspend context where CE is impossible
3. **Already covered by guard** — wrapped by `WorkerExecutionGuard`

---

## 8. Acceptance Criteria for MIT-034

- [ ] All 17 remaining sites from baseline have CE rethrow (PR 2)
- [ ] `CancellationSafe` helper exists (PR 2)
- [ ] Detekt rule blocks new violations in CI (PR 2)
- [ ] `CancellationSafetyArchitectureGuardTest` expanded to cover new patterns (PR 2)
- [ ] No `runCatching` in suspend coordinator paths without CE guard (PR 2)
- [ ] Cancellation regression tests pass (PR 2+)
