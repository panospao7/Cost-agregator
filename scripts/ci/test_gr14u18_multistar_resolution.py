"""GR-14u18 (§D6): simple-name resolution through MULTIPLE star imports.

`_resolve_type` only handled the wildcard case when a file had EXACTLY ONE star
import.  `AppDatabase.kt` has three (`…database.entity.*`, `…database.dao.*`,
`androidx.room.*`), so `RoomDatabase` stayed `unknown`, so the `super.onCreate`
inside its anonymous `RoomDatabase.Callback` failed closed into name-matching —
the tier-5 deciding edge for 137 of the 169 ambiguous rows.

The rule must stay FAIL-CLOSED: with several star imports, a name is resolved
only when exactly ONE candidate is confident (a corpus owner, or a package under
a known external root).  Two plausible candidates is ambiguity, not a guess.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder

_PATH = "app/src/main/java/com/example/Repo.kt"


def _resolve(source: str, name: str):
    builder = CallGraphBuilder(_production_contract(), {_PATH: source})
    builder.build()
    return builder._resolve_type(builder.file_models[_PATH], name)


def _source(imports: str, body: str = "") -> str:
    return "package com.example\n\n%s\n\nclass Repo {\n%s}\n" % (imports, body)


class TestStarImportResolution:
    def test_single_star_import_resolves_to_its_package(self):
        """Pre-existing behaviour: one wildcard binds the name unambiguously."""
        fqcn, origin = _resolve(
            _source("import com.example.corpus.types.*"), "Thing"
        )
        assert (fqcn, origin) == ("com.example.corpus.types.Thing", "external")

    def test_multiple_stars_resolve_when_exactly_one_is_a_corpus_owner(self):
        """The AppDatabase.kt shape: three wildcards, one confident candidate."""
        source = _source(
            "import com.example.entity.*\n"
            "import com.example.dao.*\n"
            "import androidx.room.*\n"
            "\n"
            "class Inner\n",
            "",
        )
        fqcn, origin = _resolve(source, "RoomDatabase")
        assert (fqcn, origin) == ("androidx.room.RoomDatabase", "external")

    def test_multiple_stars_stay_unknown_when_two_external_roots_match(self):
        """Fail closed: ambiguity is never resolved by guessing."""
        source = _source(
            "import androidx.room.*\n"
            "import android.database.sqlite.*\n"
        )
        fqcn, origin = _resolve(source, "SomeType")
        assert (fqcn, origin) == ("", "unknown")

    def test_multiple_stars_stay_unknown_when_no_candidate_matches(self):
        fqcn, origin = _resolve(
            _source("import com.example.a.*\nimport com.example.b.*"), "Missing"
        )
        assert (fqcn, origin) == ("", "unknown")

    def test_fully_qualified_spelling_still_resolves_without_imports(self):
        fqcn, origin = _resolve(_source(""), "androidx.room.RoomDatabase")
        assert (fqcn, origin) == ("androidx.room.RoomDatabase", "external")
