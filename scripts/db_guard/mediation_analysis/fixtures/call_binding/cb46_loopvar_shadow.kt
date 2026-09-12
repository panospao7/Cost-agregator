// CB-46: reviewer LV-pin-shadow — nested loops declaring the SAME variable
// name: the inner loop's binding wins inside the inner body (nearest
// enclosing loop).  The inner body's member call resolves exact to the
// INNER element type's implementor set; the outer loop's own body (outside
// the inner loop) resolves to the OUTER element type.
// Expected resolution: EXACT_SYNCHRONOUS (both subject calls resolve exact
// — inner to Cb46InnerTarget's implementor, outer to Cb46OuterTarget's).

package fixtures.callbinding

interface Cb46OuterTarget {
    fun purgeOuter()
}

interface Cb46InnerTarget {
    fun purgeInner()
}

class Cb46OuterImpl : Cb46OuterTarget {
    override fun purgeOuter() {
        // no-op fixture body
    }
}

class Cb46InnerImpl : Cb46InnerTarget {
    override fun purgeInner() {
        // no-op fixture body
    }
}

fun exerciseCb46() {
    val outer: List<Cb46OuterTarget> = listOf(Cb46OuterImpl())
    val inner: List<Cb46InnerTarget> = listOf(Cb46InnerImpl())
    for (target in outer) {
        target.purgeOuter()
        for (target in inner) {
            target.purgeInner()
        }
    }
}
