// CB-58: GR-14u53 SI-pos-corpus — a one-star import whose candidate IS a
// corpus owner resolves CORPUS (the mirrored discipline preserves corpus
// resolution through star imports).
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

import fixtures.callbinding.cb58.*

interface Cb58Target {
    fun foo()
}

object Cb58Holder {
    fun bar() {
        // no-op fixture body
    }
}

fun exerciseCb58() {
    Cb58Holder.bar()
}
