// HP-19: a carrier method outside the closed inline set stays uncertain
// (GR-14f fail-closed pin).  `runElsewhere` models any custom/lazy carrier
// (flow/sequence builders, executors, hand-rolled dispatch) — never
// admitted by the inline-carrier table.
// Expected resolution: ASYNC_DISPATCH.
// Expected proof: UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK.

package fixtures.helperproof

class Hp19CustomCarrierStaysAsync {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        runElsewhere {
            store.put(id, id)
        }
    }

    private fun runElsewhere(block: () -> Unit) {
        block()
    }

    fun withWriteBarrier(block: () -> Unit) {
        block()
    }

    fun exercise(id: Int) {
        withWriteBarrier { writeRow(id) }
    }
}
