// HP-23: restore-internal scope form (`RestoreInternalWriteScope.run`) —
// GR-14u25 regression pin for the THIRD sanctioned guard form.  Unlike the
// canonical direct/worker scopes, this form is legal ONLY during restore
// windows, so its proof state is distinct (PROVEN_RESTORE_INTERNAL).
//
// The writer below is reached ONLY from inside the scope, so its mutation is
// proven by `restore_internal` context PROPAGATED across the exact call edge
// (no local guard of its own).  That propagation is the specific behaviour the
// u25 pin `test_scope_context_propagates_to_the_callee` covers at unit level.
// Expected resolution: EXACT_CANONICAL_SCOPE.
// Expected proof: PROVEN_RESTORE_INTERNAL.

package fixtures.helperproof

class Hp23RestoreScope {
    suspend fun <T> run(operation: String, block: suspend () -> T): T {
        return block()
    }
}

class Hp23RestoreWriter {
    val store = mutableMapOf<Int, Int>()

    fun writeRow(id: Int) {
        store.put(id, id)
    }
}

class Hp23RestoreCaller(
    private val scope: Hp23RestoreScope,
    private val writer: Hp23RestoreWriter
) {
    suspend fun exercise(id: Int) {
        scope.run("exercise") {
            writer.writeRow(id)
        }
    }
}
