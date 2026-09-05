// HP-05: helper invoked inside a coroutine launch, not a synchronous barrier.
// Expected resolution: ASYNC_DISPATCH.
// Expected proof: UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK.

package fixtures.helperproof

class Hp05CoroutineLaunch {
    private val store = mutableMapOf<Int, Int>()

    private fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun launchWrite(dispatcher: (block: () -> Unit) -> Unit, id: Int) {
        dispatcher { writeRow(id) }
    }
}
