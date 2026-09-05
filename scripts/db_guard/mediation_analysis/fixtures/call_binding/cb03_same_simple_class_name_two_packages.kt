// CB-03: same simple class name imported from two different packages.
// Expected resolution: AMBIGUOUS_TARGET.

package fixtures.callbinding

import fixtures.alpha.Profile
import fixtures.beta.Profile

class Cb03SameSimpleName {
    fun exerciseAlpha(): Profile {
        return Profile("alpha")
    }

    fun exerciseBeta(): Profile {
        return Profile("beta")
    }
}
