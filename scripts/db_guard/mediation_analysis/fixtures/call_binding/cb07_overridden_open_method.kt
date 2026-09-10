// CB-07: open method overridden in a subclass, called via base type.
// Expected resolution: VIRTUAL_DISPATCH.

package fixtures.callbinding

open class Cb07Base {
    open fun describe(): String = "base"
}

class Cb07Derived : Cb07Base() {
    override fun describe(): String = "derived"
}

fun exerciseCb07(instance: Cb07Base): String {
    return instance.describe()
}
