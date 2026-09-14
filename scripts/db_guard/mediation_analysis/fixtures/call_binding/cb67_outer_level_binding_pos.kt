// CB-67: GR-14u55b NL-pos - a name declared ONLY at the OUTER lambda
// level: the inner level does NOT redeclare it, so the walk continues
// outward and binds at the outer level (lexical scoping).  Expected
// resolution: EXACT_SYNCHRONOUS (outer.success() resolves to
// Cb67HandleImpl via the outer binding).  Probed on both u55a
// (unresolved_target) and u55b (exact).

package fixtures.callbinding

interface Cb67Handle {
    fun success()
}

class Cb67HandleImpl : Cb67Handle {
    override fun success() {}
}

interface Cb67Runner {
    fun <T> runOperation(operationType: String, block: suspend (Cb67Handle) -> T): T
}

class Cb67RunnerImpl : Cb67Runner {
    override suspend fun <T> runOperation(operationType: String, block: suspend (Cb67Handle) -> T): T {
        return block(Cb67HandleImpl())
    }
}

fun exerciseCb67(runner: Cb67Runner) {
    runner.runOperation("X") { outer ->
        runner.runOperation("Y") { inner ->
            outer.success()
        }
    }
}
