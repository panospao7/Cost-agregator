// CB-75: GR-14u56e inline-wrapper set extension — a withRateLimit-shaped
// wrapper (private suspend fun whose body is
// `= rateLimitMutex.withLock { ...; block() }`; the block is invoked
// EXACTLY ONCE inline inside an admitted transparent withLock scope,
// never stored, never conditionally invoked) is an admitted transparent
// carrier, so its trailing lambda parses inline and the corpus method
// called inside resolves EXACT.
// Expected resolution: EXACT_CANONICAL_SCOPE (same-owner callee, the
// CB-60 precedent).

package fixtures.callbinding

interface Cb75Result

class Cb75Worker {
    private val rateLimitMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun <T> withRateLimit(block: suspend () -> T): T =
        rateLimitMutex.withLock {
            markLimited()
            block()
        }

    fun markImported() {
        // no-op fixture body
    }

    private fun markLimited() {
        // no-op fixture body
    }

    fun exercise() {
        withRateLimit {
            markImported()
            Cb75Result()
        }
    }
}
