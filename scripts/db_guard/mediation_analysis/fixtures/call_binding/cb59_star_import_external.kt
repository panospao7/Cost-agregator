// CB-59: GR-14u53 SI-pos-external — a one-star import whose candidate's
// root IS a known external root (kotlin/java/androidx/android) resolves
// EXTERNAL (the historical one-star shortcut's legitimate case — e.g.
// AppDatabase.kt's RoomDatabase via androidx.room — is preserved).  The
// receiver types as kotlin.StringBuilder (external) and the member call
// resolves as an exact external edge.
// Expected resolution: EXACT_SYNCHRONOUS.

package fixtures.callbinding

import kotlin.collections.*

interface Cb59Target {
    fun run()
}

fun exerciseCb59() {
    val builder = StringBuilder()
    builder.append("x")
}
