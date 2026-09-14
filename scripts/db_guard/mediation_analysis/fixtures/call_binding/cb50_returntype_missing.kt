// CB-50: GR-14u50 RT-neg-no-return-type — the accessor is EXPRESSION-BODIED
// with no declared return type (`fun dao() = Cb50DaoImpl()`); no return
// type is captured, so the chain fails closed and the site edge keeps the
// name-matched UNRESOLVED_TARGET edge.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb50Dao {
    fun insert(x: Int)
}

class Cb50DaoImpl : Cb50Dao {
    override fun insert(x: Int) {
        // no-op fixture body
    }
}

class Cb50Repo {
    fun dao() = Cb50DaoImpl()
}

fun exerciseCb50(repo: Cb50Repo) {
    val d = repo.dao()
    d.insert(1)
}
