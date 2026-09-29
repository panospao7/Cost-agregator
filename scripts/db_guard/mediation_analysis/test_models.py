"""Unit tests for the frozen data models of the mediation analysis slice.

Run directly with: python test_models.py
"""

import dataclasses
import os
import sys
import unittest

# Use the same canonical package under recursive pytest and direct execution.
# A bare "models" depends on cwd/sys.path and can bind an unrelated module.
_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from scripts.db_guard.mediation_analysis import models  # noqa: E402

EXPECTED_MEMBERS = {
    "ResolutionState": (
        "EXACT_SYNCHRONOUS", "EXACT_CANONICAL_SCOPE", "AMBIGUOUS_TARGET",
        "UNRESOLVED_TARGET", "VIRTUAL_DISPATCH", "INTERFACE_DISPATCH",
        "FUNCTION_REFERENCE", "ESCAPING_LAMBDA", "ASYNC_DISPATCH",
        "RECURSIVE_UNSUPPORTED", "EXTERNAL_ENTRY", "UNSUPPORTED_SYNTAX",
    ),
    "RootKind": (
        "WORKER_DO_WORK", "FRAMEWORK_CALLBACK", "PUBLIC_OR_PROTECTED_EXTERNAL",
        "TOP_LEVEL_EXTERNAL", "CONSTRUCTOR_EXTERNAL", "UNKNOWN_EXTERNAL",
    ),
    "GuardContext": ("NONE", "DIRECT_BARRIER", "WORKER_GUARD"),
    "ProofState": (
        "PROVEN_HELPER", "PROVEN_WORKER_MEDIATED", "PROVEN_RESTORE_INTERNAL",
        "COUNTEREXAMPLE_UNGUARDED_CALL_PATH", "COUNTEREXAMPLE_NON_WORKER_ROOT",
        "COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE", "UNPROVEN_EXTERNAL_ENTRY",
        "UNPROVEN_AMBIGUOUS_CALL", "UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK",
        "UNPROVEN_RECURSION", "UNSUPPORTED_SOURCE", "INFRASTRUCTURE_FAILURE",
    ),
}

EXPECTED_COUNTS = {
    "ResolutionState": 12,
    "RootKind": 6,
    "GuardContext": 3,
    "ProofState": 12,
}


class ModuleIdentityTests(unittest.TestCase):
    def test_models_uses_the_canonical_mediation_package(self):
        self.assertEqual(models.__name__, "scripts.db_guard.mediation_analysis.models")
        self.assertEqual(
            os.path.realpath(models.__file__),
            os.path.realpath(os.path.join(os.path.dirname(__file__), "models.py")),
        )


class EnumVocabularyTests(unittest.TestCase):
    def test_member_counts_match_spec(self):
        for name, count in EXPECTED_COUNTS.items():
            self.assertEqual(len(getattr(models, name)), count, name)

    def test_no_other_members_beyond_expected_vocabulary(self):
        for name, members in EXPECTED_MEMBERS.items():
            enum_cls = getattr(models, name)
            self.assertEqual(
                tuple(member.name for member in enum_cls), members, name)

    def test_unknown_proof_and_resolution_codes_rejected(self):
        with self.assertRaises(ValueError):
            models.ProofState("BOGUS")
        with self.assertRaises(ValueError):
            models.ResolutionState("BOGUS")


class FrozenDataclassTests(unittest.TestCase):
    def test_frozen_dataclasses_raise_on_mutation(self):
        node = models.CallableNode(
            path="src/main.kt", ownerFqcn="com.example.Foo", kind="class",
            method="run")
        with self.assertRaises(dataclasses.FrozenInstanceError):
            node.method = "other"
        diagnostic = models.MediationDiagnostic(code="X", target="t")
        with self.assertRaises(dataclasses.FrozenInstanceError):
            diagnostic.detail = "nope"
        result = models.MediationProofResult(
            mutationKey="m", state=models.ProofState.PROVEN_HELPER)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            result.reasonCode = "nope"


class DiagnosticShapeTests(unittest.TestCase):
    def test_diagnostic_has_only_code_target_detail_fields(self):
        names = tuple(
            field.name for field in dataclasses.fields(models.MediationDiagnostic))
        self.assertEqual(names, ("code", "target", "detail"))


if __name__ == "__main__":
    outcome = unittest.main(exit=False, verbosity=2).result
    if outcome.wasSuccessful():
        print("OK")
    else:
        raise SystemExit(1)
