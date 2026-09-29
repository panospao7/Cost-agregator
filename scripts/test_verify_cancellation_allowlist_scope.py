"""CL-22: cancellation exemptions cannot spill into other rules or callables."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_cancellation_boundaries as guard


SOURCE_PATH = "app/src/main/java/Service.kt"


def _entry(rule="G-CANCEL-01", symbol="com.example.Service.permitted()", path=SOURCE_PATH):
    return {"rule": rule, "path": path, "symbol": symbol}


def _scan(tmp_path, source, entries):
    path = tmp_path / SOURCE_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    return guard.scan_file(path, entries)


def test_unsafe_run_catching_in_executable_template_is_not_masked_out(tmp_path):
    template = "$" + '{runCatching { fetch() }.getOrDefault("fallback")}'
    source = 'package com.example\nclass Service {\n    suspend fun load(): String {\n        return "' + template + '"\n    }\n}\n'
    violations, fatal = _scan(tmp_path, source, [])
    assert not fatal
    assert any(violation.startswith("G-CANCEL-02") for violation in violations)


def test_exact_method_exemption_does_not_cover_a_sibling(tmp_path):
    violations, fatal = _scan(tmp_path, """package com.example
class Service {
    suspend fun permitted(): Int {
        try { return fetch() } catch (e: Exception) { return 0 }
    }
    suspend fun sibling(): Int {
        try { return fetch() } catch (e: Exception) { return 0 }
    }
}
""", [_entry()])
    assert not fatal
    assert len(violations) == 1
    assert "symbol=com.example.Service.sibling()" in violations[0]


@pytest.mark.parametrize("rule,remaining", [
    ("G-CANCEL-01", {"G-CANCEL-02", "G-CANCEL-03"}),
    ("G-CANCEL-02", {"G-CANCEL-03"}),
    ("G-CANCEL-03", {"G-CANCEL-02"}),
])
def test_only_the_exact_active_rule_can_be_exempted(tmp_path, rule, remaining):
    violations, fatal = _scan(tmp_path, """package com.example
class Service {
    suspend fun permitted(): Int {
        return runCatching { fetch() }
            .onFailure { logFailure() }
            .getOrDefault(0)
    }
}
""", [_entry(rule=rule)])
    assert not fatal
    assert {violation.split()[0] for violation in violations} == remaining


def test_overload_requires_its_own_exact_parameter_symbol(tmp_path):
    violations, fatal = _scan(tmp_path, """package com.example
class Service {
    suspend fun permitted(): Int {
        return runCatching { fetch() }.getOrDefault(0)
    }
    suspend fun permitted(value: Int): Int {
        return runCatching { fetch() }.getOrDefault(value)
    }
}
""", [_entry(rule="G-CANCEL-02")])
    assert not fatal
    assert len(violations) == 1
    assert "permitted(Int)" in violations[0]


@pytest.mark.parametrize("symbol", ["", "Service", "broadCatch", "*"])
def test_file_or_class_markers_do_not_authorize_a_method(tmp_path, symbol):
    violations, fatal = _scan(tmp_path, """package com.example
class Service {
    suspend fun permitted(): Int {
        return runCatching { fetch() }.getOrDefault(0)
    }
}
""", [_entry(rule="G-CANCEL-02", symbol=symbol)])
    assert not fatal
    assert len(violations) == 1


def test_outer_method_exemption_cannot_authorize_an_unresolved_local_function(tmp_path):
    violations, fatal = _scan(tmp_path, """package com.example
class Service {
    suspend fun permitted(): Int {
        suspend fun inner(): Int {
            return runCatching { fetch() }.getOrDefault(0)
        }
        return inner()
    }
}
""", [_entry(rule="G-CANCEL-02")])
    assert not fatal
    assert len(violations) == 1
    assert "symbol=<unresolved>" in violations[0]


def test_matcher_rejects_missing_rule_unknown_symbol_and_filename_fragment():
    path = SOURCE_PATH
    symbol = "com.example.Service.permitted()"
    assert guard.is_allowlisted(path, symbol, [_entry()], rule_id="G-CANCEL-01")
    assert not guard.is_allowlisted(path, "", [_entry()], rule_id="G-CANCEL-01")
    assert not guard.is_allowlisted(path, "Service", [_entry(symbol="Service")], rule_id="G-CANCEL-01")
    assert not guard.is_allowlisted(path, symbol, [_entry(rule="")], rule_id="G-CANCEL-01")
    assert not guard.is_allowlisted(path, symbol, [_entry(path="vice.kt")], rule_id="G-CANCEL-01")
    assert not guard.is_allowlisted(path, symbol, [_entry()], rule_id="G-CANCEL-02")


@pytest.mark.parametrize("entry_path", [
    "Service.kt", "java/Service.kt", "main/java/Service.kt",
    "src/main/java/Service.kt", "/repo/" + SOURCE_PATH,
    "app/src/main/java/../java/Service.kt", "app/src/main/java/*",
])
def test_path_exemption_requires_the_exact_repository_relative_path(entry_path):
    assert not guard.is_allowlisted(
        SOURCE_PATH, "com.example.Service.permitted()",
        [_entry(path=entry_path)], rule_id="G-CANCEL-01",
    )


def test_exact_path_exemption_does_not_cover_another_directory():
    assert not guard.is_allowlisted(
        "app/src/main/java/other/Service.kt", "com.example.Service.permitted()",
        [_entry()], rule_id="G-CANCEL-01",
    )


def test_basename_exemption_cannot_hide_a_scanned_violation(tmp_path):
    violations, fatal = _scan(tmp_path, """package com.example
class Service {
    suspend fun permitted(): Int {
        return runCatching { fetch() }.getOrDefault(0)
    }
}
""", [_entry(rule="G-CANCEL-02", path="Service.kt")])
    assert not fatal
    assert len(violations) == 1
    assert violations[0].startswith("G-CANCEL-02 ")


def test_comments_do_not_supply_cancellation_protection(tmp_path):
    violations, fatal = _scan(tmp_path, """package com.example
class Service {
    suspend fun permitted(): Int {
        try { return fetch() } catch (e: Exception) {
            // CancellationException and rethrowIfCancellation are NOT executed.
            return 0
        }
    }
}
""", [])
    assert not fatal
    assert any(violation.startswith("G-CANCEL-01 ") for violation in violations)


def test_unterminated_source_is_an_infrastructure_error(tmp_path, capsys):
    violations, fatal = _scan(tmp_path, 'class Service { /* unterminated', [])
    assert fatal
    assert violations == []
    assert "CANCELLATION_SOURCE_UNPARSEABLE" in capsys.readouterr().err
