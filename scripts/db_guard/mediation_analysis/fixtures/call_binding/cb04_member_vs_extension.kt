// CB-04: member function wins over a same-signature extension.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

class Cb04Member {
    fun render(): String = "member"
}

fun Cb04Member.render(): String = "extension"

fun exerciseCb04(): String {
    val target = Cb04Member()
    return target.render()
}
