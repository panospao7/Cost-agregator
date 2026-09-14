// CB-55: GR-14u52 BH-neg — hint ABSENT.  A captured receiver with NO hint
// (the mediation layer only builds hints for synthetic members whose
// captures resolve) keeps the file-level resolver's NOT_A_PROPERTY
// behavior: the receiver stays unresolved and the member call keeps the
// name-matched UNRESOLVED_TARGET edge — the pre-u52 behavior preserved.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb55Barrier {
    fun check(op: String)
}

interface Cb55Dao {
    fun insert(x: Int)
}

interface Cb55Target {
    val name: String
}

fun exerciseCb55() {
    val target = object : Cb55Target {
        override val name = "cb55"
        fun run() {
            Cb55DaoHolder55.dao.insert(1)
        }
    }
}

object Cb55DaoHolder55 {
    val dao: Cb55Dao = object : Cb55Dao {
        override fun insert(x: Int) {
            // no-op fixture body
        }
    }
}
