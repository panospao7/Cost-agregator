// CB-10: generic helper callable with several plausible type arguments.
// Expected resolution: AMBIGUOUS_TARGET.

package fixtures.callbinding

class Cb10GenericAmbiguity {
    fun <T> pick(candidate: T): T = candidate

    fun exerciseStrings(): String {
        return pick("text")
    }

    fun exerciseInts(): Int {
        return pick(5)
    }
}
