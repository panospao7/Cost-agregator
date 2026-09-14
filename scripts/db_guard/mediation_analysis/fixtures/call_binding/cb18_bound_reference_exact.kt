// CB-18: a BOUND member reference (`vm::save`) with a typed receiver and a
// unique member of that name binds exactly (GR-14u26 reference resolution).
// Before that batch the reference was always an uncertain FUNCTION_REFERENCE
// edge carrying name-matched targets.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

class Cb18ViewModel {
    fun save(value: Int) {
        // no-op fixture body
    }
}

fun exerciseCb18(vm: Cb18ViewModel) {
    val action = vm::save
    action(1)
}
