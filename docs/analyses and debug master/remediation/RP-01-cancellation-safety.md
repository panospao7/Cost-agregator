# RP-01 - Cancellation safety and guard enforcement

> **Status:** corrected implementation plan; strict gate.
> **Scope:** cancellation propagation in shared suspend paths and the static guard that enforces it.
> **Owners:** transaction lifecycle, financial-health cancellation sites, startup recovery, architecture tests.
> **Must land before:** RP-06 and RP-11. RP-02/RP-03 may rebase on the guard changes.
> **Do not include:** fabricated financial-health fallback semantics; those belong to RP-06.

## Verified contract

Every suspend path must preserve caller cancellation. A `CancellationException`, including a timeout subclass, must not be converted into `Result.failure`, `null`, a domain failure, a fabricated score, or a successful terminal result. Non-cancellation exceptions may retain their existing bounded failure behavior.

The existing `CancellationSafe.runCatchingCancellable` is **not** a suspend helper:

```kotlin
inline fun <T> runCatchingCancellable(block: () -> T): Result<T>
```

Therefore a suspending block must not be passed to it. Do not add a second overload with the same name unless its JVM signature and call-site behavior are explicitly verified. The default implementation for suspend code is an explicit `try/catch` that rethrows `CancellationException` first.

## U-001 - Transaction conversion and event writes swallow cancellation

### Confirmed behavior

`TransactionLifecycleCoordinator` uses raw `runCatching` around suspending currency conversion in the create and update paths and around several best-effort event writes. Cancellation can therefore become `null` or a fabricated event-bookkeeping value, allowing work to continue after the caller has been cancelled.

Relevant areas must be re-read at implementation time because line numbers move:

- `TransactionLifecycleCoordinator.kt`: create conversion, update conversion, and the five targeted event-write wrappers.
- `CancellationSafe.kt`: existing non-suspend helper.
- `CancellationPropagationContractTest` and `CancellationSafetyArchitectureGuardTest`.

### Required implementation

1. Replace the two suspending conversion wrappers with explicit cancellation-safe handling:

   ```kotlin
   val conversion = try {
       currencyConverter.convertAsOf(/* existing arguments */)
   } catch (e: CancellationException) {
       throw e
   } catch (e: Exception) {
       null
   }
   ```

   Preserve the existing non-cancellation fallback fields and diagnostics byte-for-byte in meaning. Do not substitute a home currency or fabricate a converted amount.

2. For each best-effort suspend event write, use a small local helper only if it is genuinely suspend-safe:

   ```kotlin
   private suspend fun <T> bestEffortEvent(block: suspend () -> T): T? = try {
       block()
   } catch (e: CancellationException) {
       throw e
   } catch (e: Exception) {
       null
   }
   ```

   The helper may be private to the coordinator. It must not use the existing non-suspend `runCatchingCancellable` with a suspend lambda. Preserve controlled diagnostics and do not expose `e.message` or stack traces.

3. Replace the `getOrDefault(false)` event-bookkeeping behavior with an explicit nullable/unknown result only if the consuming model already supports it. If the model is non-nullable, retain the existing domain contract and add a separate safe diagnostic rather than silently changing the meaning of `false`. This requires a source check before editing.

4. Keep already-correct `CancellationException` rethrows unchanged. This plan is not a broad refactor of every catch in the coordinator.

5. Add static contract entries for the create and update conversion boundaries to `CancellationPropagationContractTest`. The runtime test belongs in the coordinator test family, not in the static contract test.

### Required runtime tests

- Cancel create while the fake converter is suspended: cancellation propagates and no expense/event side effect is committed after cancellation.
- Cancel update while the fake converter is suspended: same guarantees.
- Cancel during a best-effort event write: cancellation propagates; no later dispatch or success result is returned.
- Non-cancellation conversion failure preserves the existing fallback and diagnostic behavior.
- A timeout exception is treated according to the owning operation's timeout policy; it must not be confused with caller cancellation.

## U-002 - Static cancellation guard misses suspend functions and accepts comments

### Confirmed behavior

`CancellationSafetyArchitectureGuardTest` has two weaknesses:

- suspend-function range detection can stop at a default parameter's `=` and fail to scan the function body;
- evidence detection accepts token presence, including comments, rather than proving an executable cancellation guard.

### Required implementation

1. Replace parameter parsing with balanced-parenthesis scanning. After `suspend fun`, locate the matching closing parameter parenthesis while ignoring string/character literals sufficiently for the source corpus. Only then distinguish an expression body from a block body.

2. Make evidence executable and narrow. Accept only evidence such as:

   - `catch (e: CancellationException) { throw e }`;
   - `CancellationSafe.rethrowIfCancellation(e)`;
   - a call to a verified cancellation-safe helper;
   - `ensureActive()` where it is actually in the catch path.

   A comment, string literal, import, or bare `CancellationException` token is not evidence.

3. Add fixtures inside the guard test for:

   - default-parameter suspend function with raw `runCatching`, which must fail;
   - default-parameter suspend function with executable CE rethrow, which must pass;
   - comment-only CE mention, which must fail;
   - a helper call that is explicitly allowlisted and verified.

4. Fix newly exposed production violations in the owning plan. Do not enlarge the allowlist merely to restore green. Any temporary exception must have owner, reason, issue ID, and expiry, and the allowlist must shrink over time.

## U-003 - Raw `runCatching` allowlist expiry is not enforced

Add the same expiry assertion used for `KNOWN_VIOLATIONS` to `RAW_RUN_CATCHING_ALLOWLIST`. Use an injected/fixed date or a helper accepting `today` so the test is deterministic. The test must identify every expired entry and fail before the allowlist silently becomes permanent.

The implementation must not use production wall-clock time in a way that makes the unit test flaky.

## U-006 - Startup recovery uses raw `runCatching`

Replace the suspend startup wrappers with explicit CE-safe handling or a verified suspend helper. Startup recovery is best effort, but cancellation of the startup scope must propagate. Non-cancellation failures retain bounded warning diagnostics with controlled reason codes.

The affected calls include stale worker recovery and restore-journal import. Verify each call's return type before replacing the wrapper; do not turn a recovery failure into a false successful recovery state.

## Cross-plan ownership matrix

| Area | RP-01 disposition | Other plan |
|---|---|---|
| Transaction create/update cancellation | Fix here | RP-11 may change control flow later; rebase, do not duplicate catch edits |
| FinancialHealthScoreV2 CE propagation | Fix only CE conversion here | RP-06 owns unavailable/fabricated-score semantics |
| Startup recovery CE propagation | Fix here | RP-02/RP-03 may add recovery work but must use this contract |
| Worker cancellation | Not broadly refactored here | RP-16 owns worker terminal semantics |
| OCR timeout versus caller cancellation | Not fixed here | RP-13 owns typed timeout/retry behavior |
| UI `viewModelScope` cancellation | Not blanket-fixed here | Individual owning plans must preserve CE |

## Stop conditions

Stop and re-evaluate before implementation if:

- the consuming event-bookkeeping type cannot represent unknown write failure without changing a public contract;
- a proposed helper requires a new suspend overload with unresolved JVM signature behavior;
- a catch is used to convert `TimeoutCancellationException` without proving whether the parent scope is still active;
- the guard requires a broad allowlist increase.

## Validation

Run sequentially, with output captured:

```text
./gradlew :app:testDebugUnitTest --tests "*CancellationSafetyArchitectureGuard*" --tests "*CancellationPropagation*" --console=plain
./gradlew :app:testDebugUnitTest --tests "*TransactionLifecycleCoordinator*" --tests "*FinancialHealthScoreV2*" --console=plain
./gradlew :app:compileDebugKotlin --console=plain
```

Completion requires:

- static guard passes without an expanded permanent allowlist;
- runtime cancellation tests pass;
- non-cancellation behavior is unchanged;
- the final diff does not include RP-06 fallback changes or RP-11 lifecycle semantics.
