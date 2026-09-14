// CB-64: GR-14u55b NL-shadow - the same param name declared at TWO lambda
// levels.  The INNER level redeclares `x` over a GENERIC component
// (runOperationGeneric's block is `suspend (T) -> T`): the declared-but-
// untypable inner binding FAILS CLOSED (u55c ISSUE-1 rule - no outer
// substitution; Kotlin shadowing means the inner declaration IS the
// binding).  Expected resolution: UNRESOLVED_TARGET (fail-closed).
// Probed: pre-fix code bound the outer Cb64OuterHandle (fail-open exact);
// the fixed walk stops at the inner level.

package fixtures.callbinding

interface Cb64OuterHandle {
    fun ping()
}

class Cb64OuterHandleImpl : Cb64OuterHandle {
    override fun ping() {}
}

interface Cb64Runner {
    fun <T> runOperation(operationType: String, block: suspend (Cb64OuterHandle) -> T): T
    fun <T> runOperationGeneric(operationType: String, block: suspend (T) -> T): T
}

class Cb64RunnerImpl : Cb64Runner {
    override suspend fun <T> runOperation(operationType: String, block: suspend (Cb64OuterHandle) -> T): T {
        return block(Cb64OuterHandleImpl())
    }
    override suspend fun <T> runOperationGeneric(operationType: String, block: suspend (T) -> T): T {
        TODO()
    }
}

fun exerciseCb64(runner: Cb64Runner) {
    runner.runOperation("X") { x ->
        runner.runOperationGeneric("Y") { x ->
            x.ping()
        }
    }
}
