// CB-02: call inside a nested owner resolves against the enclosing scope chain.
// Expected resolution: EXACT_CANONICAL_SCOPE.

package fixtures.callbinding

class Cb02Outer {
    fun outerValue(): Int = 3

    class Cb02Inner {
        fun innerValue(): Int = 4

        fun exercise(): Int {
            return innerValue() + Cb02Outer().outerValue()
        }
    }
}
