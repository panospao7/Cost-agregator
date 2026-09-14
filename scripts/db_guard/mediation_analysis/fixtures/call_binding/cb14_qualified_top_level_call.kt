// CB-14: top-level function called with its fully qualified package path.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

fun exerciseCb14(): String {
    return fixtures.alpha.qualifiedTopLevel()
}
