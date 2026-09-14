// CB-32: GR-14u49 design fixture 2 — phantom-call suppression negative
// pin.  `object : Cb32Target { ... }` must NOT produce a call record for
// the supertype token (verified by probe: zero phantom call records named
// Cb32Target corpus-wide in this fixture).  The manifest row pins the
// file's own marker call (the fixture parses and the enclosing callable
// models); the phantom ABSENCE itself is a structural property documented
// in the GR-14u49 manifest (the runner's subject-call vocabulary cannot
// express "no edge exists" without a runner change, which is out of scope
// for this batch).

package fixtures.callbinding

interface Cb32Target {
    val name: String
}

fun cb32Marker() {
    // no-op fixture body
}

fun exerciseCb32() {
    cb32Marker()
    val target = object : Cb32Target {
        override val name = "cb32"
    }
}
