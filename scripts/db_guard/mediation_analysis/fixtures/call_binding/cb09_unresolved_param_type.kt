// CB-09: parameter type is not declared anywhere in the fixture set.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

class Cb09UnresolvedParam {
    fun exercise(input: MysteryPayload): Int {
        return input.compute()
    }
}
