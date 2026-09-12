// CB-51: GR-14u50 RT-neg-ambiguous — TWO same-name members with DIFFERENT
// declared return types on the receiver's owner; the distinct-return-type
// gate fails the chain and the site edge keeps the name-matched
// UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb51DaoA {
    fun insert(x: Int)
}

interface Cb51DaoB {
    fun insert(x: Int)
}

class Cb51DaoAImpl : Cb51DaoA {
    override fun insert(x: Int) {
        // no-op fixture body
    }
}

class Cb51Repo {
    fun dao(): Cb51DaoA = Cb51DaoAImpl()
    fun dao(other: Int): Cb51DaoB = object : Cb51DaoB {
        override fun insert(x: Int) {
            // no-op fixture body
        }
    }
}

fun exerciseCb51(repo: Cb51Repo) {
    val d = repo.dao()
    d.insert(1)
}
