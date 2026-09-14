// CB-56: GR-14u52 BH-neg-override — the resolution-ORDER pin at the edge
// layer: the enclosing function declares `val x: Cb56Dao` as a LOCAL val
// (a real declaration the file-level resolver's _PROP_RE sees), and the
// member call resolves through the DECLARED type's single implementor —
// any (hypothetical) hint for x would be ignored because the declaration
// path wins.  HONEST SCOPE: this fixture pins the u49 callgraph
// resolution ORDER at the edge layer (it passes identically at base) —
// the resolver-level declaration-beats-hint pin lives in
// scripts/ci/test_gr14u52_receiver_hints.py
// (TestReceiverTypeResolverHints.test_declaration_beats_hint).
// Expected resolution: EXACT_SYNCHRONOUS (via the declared local's type).

package fixtures.callbinding

interface Cb56Dao {
    fun insert(x: Int)
}

class Cb56DaoImpl : Cb56Dao {
    override fun insert(x: Int) {
        // no-op fixture body
    }
}

interface Cb56Target {
    val name: String
}

fun exerciseCb56() {
    val x: Cb56Dao = Cb56DaoImpl()
    val target = object : Cb56Target {
        override val name = "cb56"
        fun run() {
            x.insert(1)
        }
    }
}
