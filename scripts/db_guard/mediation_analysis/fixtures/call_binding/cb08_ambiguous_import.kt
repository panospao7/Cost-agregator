// CB-08: two star-imported packages both declare the called name.
// Expected resolution: AMBIGUOUS_TARGET.

package fixtures.callbinding

import fixtures.alpha.*
import fixtures.beta.*

class Cb08AmbiguousImport {
    fun exerciseAlpha(): String {
        return helperName()
    }

    fun exerciseBeta(): String {
        return helperName()
    }
}
