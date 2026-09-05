// CB-05: call targets a method the receiver type does not declare.
// The receiver FQCN is known but has no such member.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

class Cb05Receiver {
    fun known(): Int = 1

    fun alsoKnown(): Int = 2
}

fun exerciseCb05(): Int {
    val receiver = Cb05Receiver()
    return receiver.missing()
}
