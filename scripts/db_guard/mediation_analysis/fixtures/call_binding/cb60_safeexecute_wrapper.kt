// CB-60: GR-14u54 inline-wrapper set extension — a safeExecute-shaped
// wrapper (private suspend fun whose body is `return try { block() }
// catch { ... }`; the block is invoked EXACTLY ONCE inline, never stored,
// never conditionally invoked) is an admitted transparent carrier, so its
// trailing lambda parses inline and the corpus method called inside
// resolves EXACT.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb60Result

class Cb60Worker {
    private suspend fun safeExecute(
        label: String,
        block: suspend () -> Cb60Result
    ): Cb60Result {
        return try {
            block()
        } catch (e: Exception) {
            Cb60Result()
        }
    }

    fun markImported() {
        // no-op fixture body
    }

    fun exercise() {
        safeExecute("op") {
            markImported()
            Cb60Result()
        }
    }
}
