// CB-53: GR-14u50 RT-chain — a TWO-segment chain (`obj.a().b()`) where both
// accessors declare explicit return types; the val types as b's return
// type and the site edge resolves exact.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

interface Cb53Dao {
    fun insert(x: Int)
}

class Cb53DaoImpl : Cb53Dao {
    override fun insert(x: Int) {
        // no-op fixture body
    }
}

class Cb53Middle {
    fun dao(): Cb53Dao = Cb53DaoImpl()
}

class Cb53Repo {
    fun middle(): Cb53Middle = Cb53Middle()
}

fun exerciseCb53(repo: Cb53Repo) {
    val d = repo.middle().dao()
    d.insert(1)
}
