"""CL-22: an unreadable exemption contract is never an empty successful scan."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_allowlist_compliance as guard


@pytest.mark.parametrize("contents", [
    "[unterminated", "scalar", "42", "null", "# only a comment\n",
    "{}", "unknown: []", "allowed_writers: scalar", "allowed_writers: [false]",
    "release_block_tests: {}", "release_block_tests: [null]", "[valid, 3]",
    "allowed_writers: []\nrelease_block_tests: []",
    "allowed_writers: []\nallowed_writers: []",
    "- path: First.kt\n  path: Second.kt\n  reason: duplicate identifier",
])
def test_malformed_or_unsupported_yaml_fails_closed(tmp_path, contents):
    path = tmp_path / "allowlist.yml"
    path.write_text(contents, encoding="utf-8")
    with pytest.raises(guard.AllowlistInputError, match="^ALLOWLIST_INPUT_INVALID$"):
        guard.parse_db_access_allowlist(str(path))


@pytest.mark.parametrize("contents", ["[]", "allowed_writers: []", "release_block_tests: []"])
def test_explicit_empty_lists_remain_valid(tmp_path, contents):
    path = tmp_path / "allowlist.yml"
    path.write_text(contents, encoding="utf-8")
    assert guard.parse_db_access_allowlist(str(path)) == []


def test_missing_yaml_parser_is_infrastructure_failure(tmp_path, monkeypatch):
    path = tmp_path / "allowlist.yml"
    path.write_text("[]", encoding="utf-8")
    monkeypatch.setitem(sys.modules, "yaml", None)
    with pytest.raises(guard.AllowlistInputError, match="^ALLOWLIST_PARSER_UNAVAILABLE$"):
        guard.parse_db_access_allowlist(str(path))


def test_unreadable_yaml_does_not_leak_exception_text(tmp_path, monkeypatch):
    def denied(*args, **kwargs):
        raise PermissionError("sensitive-path-and-payload")
    monkeypatch.setattr("builtins.open", denied)
    with pytest.raises(guard.AllowlistInputError) as raised:
        guard.parse_db_access_allowlist(str(tmp_path / "allowlist.yml"))
    assert str(raised.value) == "ALLOWLIST_INPUT_UNREADABLE"
    assert raised.value.__suppress_context__


@pytest.mark.parametrize("kind", ["yaml", "text"])
@pytest.mark.parametrize("fail_flag", [False, True])
def test_missing_required_input_exits_two_without_pass(tmp_path, monkeypatch, capsys, kind, fail_flag):
    path = str(tmp_path / "missing.allowlist")
    monkeypatch.setattr(guard, "YAML_ALLOWLISTS", [path] if kind == "yaml" else [])
    monkeypatch.setattr(guard, "TEXT_ALLOWLISTS", [path] if kind == "text" else [])
    monkeypatch.setattr(sys, "argv", ["guard"] + (["--fail-on-violation"] if fail_flag else []))
    with pytest.raises(SystemExit) as raised:
        guard.main()
    assert raised.value.code == 2
    output = capsys.readouterr()
    assert "PASS:" not in output.out
    assert "ALLOWLIST_INPUT_MISSING" in output.err


@pytest.mark.parametrize("fail_flag", [False, True])
def test_malformed_required_yaml_exits_two_without_pass(tmp_path, monkeypatch, capsys, fail_flag):
    path = tmp_path / "allowlist.yml"
    path.write_text("allowed_writers: [false]", encoding="utf-8")
    monkeypatch.setattr(guard, "YAML_ALLOWLISTS", [str(path)])
    monkeypatch.setattr(guard, "TEXT_ALLOWLISTS", [])
    monkeypatch.setattr(sys, "argv", ["guard"] + (["--fail-on-violation"] if fail_flag else []))
    with pytest.raises(SystemExit) as raised:
        guard.main()
    assert raised.value.code == 2
    output = capsys.readouterr()
    assert "PASS:" not in output.out
    assert "ALLOWLIST_INPUT_INVALID" in output.err
