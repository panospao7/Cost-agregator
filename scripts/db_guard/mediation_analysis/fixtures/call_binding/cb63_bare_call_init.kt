// CB-63: GR-14u55b NL-pos — bare call-expression initializer typing.  A
// local val initialized by a RECEIVERLESS member call on the enclosing
// owner (`val run = start(...)`) types through the callee's EXPLICIT
// declared return type (exactly-one-member gate; arguments allowed — no
// overload ambiguity), so the member calls on it resolve exact.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb63Handle {
    fun success()
}

class Cb63Owner {
    fun start(op: String): Cb63Handle = Cb63HandleImpl()

    fun exercise(op: String) {
        val run = start(op)
        run.success()
    }
}

class Cb63HandleImpl : Cb63Handle {
    override fun success() {
        // no-op fixture body
    }
}
