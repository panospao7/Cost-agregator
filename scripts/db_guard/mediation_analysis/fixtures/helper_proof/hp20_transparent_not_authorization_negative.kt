// HP-20: transparency is not authorization (GR-14f fail-closed pin).
// The mutation sits inside an inline carrier, but the only caller never
// enters a barrier — the row must stay a definite counterexample, exactly
// as it would without any carrier admission.
// Expected resolution: EXACT_SYNCHRONOUS.
// Expected proof: COUNTEREXAMPLE_UNGUARDED_CALL_PATH.

package fixtures.helperproof

class Hp20TransparentNotAuthorization {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        id.let { value ->
            store.put(value, value)
        }
    }

    fun unguardedWrite(id: Int) {
        writeRow(id)
    }
}
