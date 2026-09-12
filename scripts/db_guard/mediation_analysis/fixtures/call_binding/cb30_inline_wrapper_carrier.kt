// CB-30: GR-14u48 inline-wrapper set extension — a guardTerminal-shaped
// private wrapper (block invoked EXACTLY ONCE inline inside
// withContext/withTimeout, never stored or conditionally invoked) is an
// admitted transparent carrier, so its trailing lambda parses inline and
// the corpus method called inside resolves EXACT.
// Expected resolution: EXACT_CANONICAL_SCOPE (the corpus method is an
// owner member reached unqualified — canonical scope resolution).

package fixtures.callbinding

class Cb30TerminalOutcome

class Cb30Worker {
    private suspend fun <T> withBoundedTerminalWrite(block: suspend () -> T): T? {
        return withContext {
            withTimeout {
                block()
            }
        }
    }

    private suspend fun guardTerminal(
        run: String,
        intendedStatus: String,
        block: suspend () -> Cb30TerminalOutcome
    ) {
        withBoundedTerminalWrite(block)
    }

    fun markImported() {
        // no-op fixture body
    }

    fun exercise(run: String) {
        guardTerminal(run, "SUCCESS") {
            markImported()
            Cb30TerminalOutcome()
        }
    }
}
