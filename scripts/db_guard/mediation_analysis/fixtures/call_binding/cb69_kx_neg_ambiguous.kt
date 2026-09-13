// CB-69: GR-14u55 KX-neg-ambiguous — the fiction-dissolution pin.  A file
// star-importing BOTH `java.util.*` and `kotlinx.coroutines.flow.*` with an
// unresolvable simple name (`emitNow`): pre-u55 the multi-star branch picked
// java.util as the single known-root candidate and fabricated a nonexistent
// FQCN (`java.util.MutableStateFlow` — the CashFlowCalendarViewModel
// `_state.update{}` shape); post-u55 both candidates are known-root, so two
// candidates -> honest ambiguity.  The receiver-typed member call keeps the
// fail-closed UNRESOLVED_TARGET edge (not a fabricated exact).
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

import java.util.*
import kotlinx.coroutines.flow.*

interface Cb69Sink {
    fun drain()
}

class Cb69Holder {
    fun exercise() {
        val flow: MutableStateFlow<Int> = MutableStateFlow(1)
        flow.emitNow(2)
        Cb69SinkImpl().drain()
    }
}

class Cb69SinkImpl : Cb69Sink {
    override fun drain() {}
}
