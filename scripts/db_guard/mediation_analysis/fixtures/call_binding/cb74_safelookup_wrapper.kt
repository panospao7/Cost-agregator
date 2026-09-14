// CB-74: GR-14u56d inline-wrapper set extension — a safeLookup-shaped
// wrapper (private suspend fun whose body is `= try { block() } catch
// (e: CancellationException) { throw e } catch (e: Exception) { ... }`;
// the block is invoked EXACTLY ONCE inline, never stored, never
// conditionally invoked) is an admitted transparent carrier, so its
// trailing lambda parses inline and the corpus method called inside
// resolves EXACT.
// Expected resolution: EXACT_CANONICAL_SCOPE (same-owner callee, the
// CB-60 precedent).

package fixtures.callbinding

interface Cb74Result

class Cb74Worker {
    private suspend fun safeLookup(
        name: String,
        block: suspend () -> Cb74Result
    ): Cb74Result = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Cb74Result()
    }

    fun markImported() {
        // no-op fixture body
    }

    fun exercise() {
        safeLookup("op") {
            markImported()
            Cb74Result()
        }
    }
}
