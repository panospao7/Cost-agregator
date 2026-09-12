// CB-49: GR-14u50 RT-pos — return-type capture + chain typing.  The corpus
// class declares `fun dao(): Cb49Dao` with an EXPLICIT return type; the
// caller's local val initializer is the no-arg chain `obj.dao()`, so the
// val types as Cb49Dao and the site edge resolves exact to the interface's
// single implementor member.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb49Dao {
    fun insert(x: Int)
}

class Cb49DaoImpl : Cb49Dao {
    override fun insert(x: Int) {
        // no-op fixture body
    }
}

class Cb49Repo {
    fun dao(): Cb49Dao = Cb49DaoImpl()
}

fun exerciseCb49(repo: Cb49Repo) {
    val d = repo.dao()
    d.insert(1)
}
