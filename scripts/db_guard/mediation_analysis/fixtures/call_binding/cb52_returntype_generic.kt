// CB-52: GR-14u50 RT-neg-generic — the accessor's declared return type is
// the callable's OWN generic parameter (`fun <T> first(): T`); the generic
// component fails to resolve to exactly one corpus FQCN or known external,
// so the INFERRED val's chain fails closed and the site edge keeps the
// name-matched UNRESOLVED_TARGET edge.  (The val is deliberately
// un-annotated — an explicit `: T` annotation would type it through the
// annotation path, which is a different mechanism.)
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

interface Cb52Dao {
    fun insert(x: Int)
}

class Cb52Repo {
    fun <T> first(): T = throw IllegalStateException("no-op fixture body")
}

fun exerciseCb52(repo: Cb52Repo) {
    val d = repo.first()
    d.insert(1)
}
