// CB-11: exact top-level function imported unambiguously.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

import fixtures.alpha.exactTopLevel

fun exerciseCb11(): String {
    return exactTopLevel()
}
