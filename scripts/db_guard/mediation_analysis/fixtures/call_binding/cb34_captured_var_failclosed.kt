// CB-34: GR-14u49 design fixture 4 — captured-local fail-closed.  The anon
// member captures an enclosing `var` (mutable binding); capture typing
// refuses (var captures never pass), so the member's call on the captured
// receiver keeps the name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb34Dao {
    fun purge(cutoff: Long)
}

interface Cb34Target {
    val name: String
}

fun exerciseCb34() {
    var capturedDao: Cb34Dao? = Cb34DaoImpl34()
    val target = object : Cb34Target {
        override val name = "cb34"
        fun run() {
            capturedDao?.purge(0L)
        }
    }
}

class Cb34DaoImpl34 : Cb34Dao {
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}
