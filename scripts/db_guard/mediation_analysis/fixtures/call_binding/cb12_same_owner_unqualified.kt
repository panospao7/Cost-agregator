// CB-12: unqualified call to a member of the same enclosing owner.
// Expected resolution: EXACT_CANONICAL_SCOPE.

package fixtures.callbinding

class Cb12SameOwner {
    fun base(): Int = 9

    fun exercise(): Int {
        return base() + 1
    }
}
