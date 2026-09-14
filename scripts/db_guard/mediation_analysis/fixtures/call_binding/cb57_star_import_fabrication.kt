// CB-57: GR-14u53 SI-neg-fabrication — the single-star-import fabrication
// regression pin.  A one-star-import file (`import androidx.work.*`) with
// an unresolvable simple-name receiver whose candidate root is NOT a known
// external root must NOT resolve as exact-external: the star-import branch
// now mirrors the multi-star confidence discipline (corpus owner or
// known-root external, else unknown — fail closed).
// NOTE: `androidx` IS a known external root, so this fixture uses a
// project-local star import (`import fixtures.starfix53.*`) whose
// candidate root `fixtures` is not a known external root — the exact
// fabrication shape the defect produced.
// Expected resolution: UNRESOLVED_TARGET.

package fixtures.callbinding

import fixtures.starfix53.*

interface Cb57Target {
    fun foo()
}

fun exerciseCb57() {
    target.foo()
}
