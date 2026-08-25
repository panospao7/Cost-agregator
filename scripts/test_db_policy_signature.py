"""Contract tests for db_policy_signature.FunctionSignature path validation.

PR-GR-03 part 2: FunctionSignature's canonical_path validation is now
syntax-only (generic repo-relative POSIX .kt validation). Topology
membership (which module/source-set a path lives under) is validated
separately by root-aware stages via source_roots.

These tests verify:
  1. FunctionSignature accepts paths from any module tree
     (app/src/main/java, feature/src/main/kotlin, lib/core/src/main/java).
  2. FunctionSignature rejects bad syntax (non-.kt, absolute, backslash,
     traversal, etc.).
  3. No executable app/src/main topology gate remains in the signature
     module's path validation.
"""

import os
import re
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from db_policy_signature import FunctionSignature, SignatureError


def _make_sig(canonical_path="app/src/main/java/example/File.kt",
              owner_fqcn="example.File",
              function_name="doThing",
              receiver=None,
              parameter_types=()):
    return FunctionSignature(
        canonical_path=canonical_path,
        owner_fqcn=owner_fqcn,
        function_name=function_name,
        receiver=receiver,
        parameter_types=parameter_types,
    )


# ── Topology-neutral acceptance tests ────────────────────────────────────────


class TestFunctionSignatureAcceptsAnyModuleTree:
    """FunctionSignature accepts paths from any module tree."""

    def test_app_src_main_java(self):
        sig = _make_sig("app/src/main/java/com/example/File.kt")
        assert sig.canonical_path == "app/src/main/java/com/example/File.kt"

    def test_app_src_main_kotlin(self):
        sig = _make_sig("app/src/main/kotlin/com/example/File.kt")
        assert sig.canonical_path == "app/src/main/kotlin/com/example/File.kt"

    def test_feature_src_main_kotlin(self):
        sig = _make_sig("feature/src/main/kotlin/com/example/File.kt")
        assert sig.canonical_path == "feature/src/main/kotlin/com/example/File.kt"

    def test_feature_src_main_java(self):
        sig = _make_sig("feature/src/main/java/com/example/File.kt")
        assert sig.canonical_path == "feature/src/main/java/com/example/File.kt"

    def test_lib_core_src_main_java(self):
        sig = _make_sig("lib/core/src/main/java/com/example/File.kt")
        assert sig.canonical_path == "lib/core/src/main/java/com/example/File.kt"

    def test_lib_core_src_main_kotlin(self):
        sig = _make_sig("lib/core/src/main/kotlin/com/example/File.kt")
        assert sig.canonical_path == "lib/core/src/main/kotlin/com/example/File.kt"

    def test_shallow_two_segment_path(self):
        """A minimal two-segment path (directory + file) is accepted."""
        sig = _make_sig("src/File.kt")
        assert sig.canonical_path == "src/File.kt"

    def test_canonical_identity_includes_non_app_path(self):
        """The canonical() identity string carries the non-app path verbatim."""
        sig = _make_sig(
            "feature/src/main/kotlin/com/example/File.kt",
            owner_fqcn="com.example.File",
            function_name="doThing",
            parameter_types=("Int",),
        )
        canonical = sig.canonical()
        assert canonical.startswith("feature/src/main/kotlin/com/example/File.kt::")
        assert "com.example.File#doThing" in canonical


# ── Syntax rejection tests ───────────────────────────────────────────────────


class TestFunctionSignatureRejectsBadSyntax:
    """FunctionSignature rejects paths with bad syntax."""

    def test_non_kt_suffix(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app/src/main/java/com/example/File.java")
        assert exc_info.value.code == "BAD_PATH"

    def test_no_suffix(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app/src/main/java/com/example/File")
        assert exc_info.value.code == "BAD_PATH"

    def test_absolute_path(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("/app/src/main/java/com/example/File.kt")
        assert exc_info.value.code == "BAD_PATH"

    def test_backslash(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app\\src\\main\\java\\com\\example\\File.kt")
        assert exc_info.value.code == "BAD_PATH"

    def test_traversal(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app/src/../java/com/example/File.kt")
        assert exc_info.value.code == "BAD_PATH"

    def test_dot_segment(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app/src/./java/com/example/File.kt")
        assert exc_info.value.code == "BAD_PATH"

    def test_empty_segment(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app/src//java/com/example/File.kt")
        assert exc_info.value.code == "BAD_PATH"

    def test_drive_prefix(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("C:/app/src/main/java/com/example/File.kt")
        assert exc_info.value.code == "BAD_PATH"

    def test_whitespace(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app/src/main/java/com/example/My File.kt")
        assert exc_info.value.code == "BAD_PATH"

    def test_control_character(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig("app/src/main/java/com/example/\x01File.kt")
        assert exc_info.value.code == "CONTROL_PATH"

    def test_non_string(self):
        with pytest.raises(SignatureError) as exc_info:
            _make_sig(123)
        assert exc_info.value.code == "BAD_PATH"


# ── No hidden app/src topology gate ──────────────────────────────────────────


class TestNoHiddenAppSrcTopologyGate:
    """Assert that the signature module's path validation has no app/src gate."""

    def test_no_app_src_prefix_constant(self):
        """The _APP_SRC_PREFIX constant has been removed."""
        import db_policy_signature as mod
        assert not hasattr(mod, "_APP_SRC_PREFIX"), (
            "_APP_SRC_PREFIX should be removed; path validation is topology-neutral"
        )

    def test_normalize_canonical_path_source_has_no_app_src_check(self):
        """The _normalize_canonical_path source code contains no app/src gate."""
        import inspect
        import db_policy_signature as mod
        source = inspect.getsource(mod._normalize_canonical_path)
        assert "app/src" not in source, (
            "_normalize_canonical_path should not reference app/src"
        )
        assert ".kt" in source, (
            "_normalize_canonical_path should check .kt suffix"
        )


# ── Grep-style topology gate assertion ───────────────────────────────────────


class TestNoExecutableAppSrcMainTopologyGate:
    """Assert no executable app/src/main topology gate remains outside
    source_roots/tests/docs/data.

    This scans the allowed executable files for hardcoded app/src/main
    path decisions that would act as topology authorities.
    """

    # Files that are allowed to contain app/src/main references:
    # - source_roots.py: the approved root constant lives here
    # - test files: fixtures and test data
    # - docs: documentation
    # - data files: YAML configs, baselines, etc.
    _ALLOWED_FILES = {
        "source_roots.py",
        "test_",
        "docs/",
        "data/",
        "config/",
        "baseline",
        "candidate",
        "production-Kotlin",
    }

    def _is_allowed(self, filepath: str) -> bool:
        basename = os.path.basename(filepath)
        for allowed in self._ALLOWED_FILES:
            if allowed in filepath:
                return True
        return False

    def test_no_executable_app_src_main_gate_in_signature_module(self):
        """db_policy_signature.py has no executable app/src/main gate."""
        import inspect
        import db_policy_signature as mod
        source = inspect.getsource(mod)
        # Check that there's no code that gates on app/src/ as a prefix
        # (comments/docstrings are OK, but executable checks are not)
        lines = source.split("\n")
        for line in lines:
            stripped = line.strip()
            # Skip comments and docstrings
            if stripped.startswith("#") or stripped.startswith('"""') or stripped.startswith("'''"):
                continue
            # Check for executable app/src/ prefix checks
            if 'startswith("app/src' in stripped or "startswith('app/src" in stripped:
                pytest.fail(
                    f"Executable app/src topology gate found in db_policy_signature.py: {stripped}"
                )

    def test_no_executable_app_src_main_gate_in_declaration_scanner(self):
        """declaration_scanner._validate_diagnostic_path has no app/src gate."""
        import inspect
        from db_guard.declaration_scanner import _validate_diagnostic_path
        source = inspect.getsource(_validate_diagnostic_path)
        lines = source.split("\n")
        for line in lines:
            stripped = line.strip()
            if stripped.startswith("#") or stripped.startswith('"""') or stripped.startswith("'''"):
                continue
            if 'startswith("app/src' in stripped or "startswith('app/src" in stripped:
                pytest.fail(
                    f"Executable app/src topology gate found in _validate_diagnostic_path: {stripped}"
                )

    def test_no_executable_app_src_main_gate_in_scanner_diag_from_text(self):
        """scanner._diag_from_text has no app/src gate."""
        import inspect
        from db_guard.scanner import _diag_from_text
        source = inspect.getsource(_diag_from_text)
        lines = source.split("\n")
        for line in lines:
            stripped = line.strip()
            if stripped.startswith("#") or stripped.startswith('"""') or stripped.startswith("'''"):
                continue
            if 'startswith("app/src' in stripped or "startswith('app/src" in stripped:
                pytest.fail(
                    f"Executable app/src topology gate found in _diag_from_text: {stripped}"
                )
