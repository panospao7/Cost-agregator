// CB-31: GR-14u49 design fixture 1 — anon-object member modelling
// positive.  The member function of an anonymous object becomes a synthetic
// corpus callable; the member's call on a CAPTURED val parameter
// (capture typing) resolves EXACT to the parameter type's single
// implementor member.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb31Dao {
    fun purge(cutoff: Long)
}

class Cb31DaoImpl : Cb31Dao {
    override fun purge(cutoff: Long) {
        // no-op fixture body
    }
}

interface Cb31Target {
    val name: String
}

fun exerciseCb31(dao: Cb31Dao) {
    val target = object : Cb31Target {
        override val name = "cb31"
        fun run() {
            dao.purge(0L)
        }
    }
}
