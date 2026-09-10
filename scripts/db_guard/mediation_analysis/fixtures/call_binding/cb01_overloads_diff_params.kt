// CB-01: overloads with different parameter types on one owner.
// Expected resolution: EXACT_SYNCHRONOUS (overload selected by argument types).

package fixtures.callbinding

class Cb01Overloads {
    fun send(count: Int): Int = count

    fun send(label: String): String = label

    fun exerciseInt(): Int {
        return send(7)
    }

    fun exerciseLabel(): String {
        return send("receipt")
    }
}
