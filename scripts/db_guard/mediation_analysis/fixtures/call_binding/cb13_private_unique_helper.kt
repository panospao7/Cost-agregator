// CB-13: private helper with a unique name inside its owner.
// Expected resolution: EXACT_CANONICAL_SCOPE.

package fixtures.callbinding

class Cb13PrivateHelper {
    private fun uniqueNormalize(value: Int): Int = value + 1

    fun exercise(): Int {
        return uniqueNormalize(4)
    }
}
