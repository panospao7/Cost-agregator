// CB-54: GR-14u52 BH-pos — receiver hints for a captured val.  The anon
// member captures an enclosing `val barrier` PARAMETER (never a `val`
// declaration in the file), and the member calls `barrier.check(...)` +
// a DAO mutation after it.  HONEST SCOPE: this fixture pins the u49
// callgraph CAPTURE behavior at the EDGE layer (it passes identically at
// base) — it does NOT exercise the GR-12 hint mechanism itself (hints
// live in the bridge, below the edge layer).  The proof-layer expression
// is covered by the BOARD evidence (the 10 RetentionModule rows moved to
// proven_helper/localGuard direct) and by the unit tests in
// scripts/ci/test_gr14u52_receiver_hints.py.
// Expected resolution: EXACT_SYNCHRONOUS (subject = the member's DAO
// mutation via the captured dao — capture typing, u49).

package fixtures.callbinding

interface Cb54Barrier {
    fun check(op: String)
}

interface Cb54Dao {
    fun insert(x: Int)
}

class Cb54DaoImpl : Cb54Dao {
    override fun insert(x: Int) {
        // no-op fixture body
    }
}

interface Cb54Target {
    val name: String
}

fun exerciseCb54(barrier: Cb54Barrier, dao: Cb54Dao) {
    val target = object : Cb54Target {
        override val name = "cb54"
        fun run() {
            barrier.check("RetentionModule.Cb54Dao.purge")
            dao.insert(1)
        }
    }
}
