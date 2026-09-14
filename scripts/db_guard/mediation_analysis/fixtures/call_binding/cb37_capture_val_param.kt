// CB-37: GR-14u49 design fixture 7 — capture-typing positive.  The anon
// member captures an outer `val` PARAMETER with a resolvable corpus type;
// the captured receiver resolves and the member's DAO edge is exact.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb37Dao {
    fun purge(cutoff: Long)
}

class Cb37DaoImpl : Cb37Dao {
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

interface Cb37Target {
    val name: String
}

fun exerciseCb37(dao: Cb37Dao) {
    val target = object : Cb37Target {
        override val name = "cb37"
        fun run() {
            dao.purge(0L)
        }
    }
}
