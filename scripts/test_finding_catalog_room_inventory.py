"""Catalog contract for controlled Room inventory diagnostics."""

from __future__ import annotations

import ast
import re
from pathlib import Path

from scripts.db_guard.dao_accessors import (
    ERROR_AMBIGUOUS_DECLARATION,
    ERROR_AMBIGUOUS_METHOD,
    ERROR_ANNOTATION_SCOPE_UNRESOLVED,
    ERROR_BAD_PATH,
    ERROR_INVALID_INPUT,
    ERROR_MISSING_DECLARATION,
    ERROR_UNSUPPORTED_DECLARATION,
    ERROR_UNSUPPORTED_METHOD,
)
from scripts.ci.finding_rule_catalog import DIAGNOSTIC_CODES, is_known_diagnostic

# Related infrastructure diagnostics (not DB_ROOM_-prefixed) that the Room
# inventory can emit and that the docs table therefore enumerates alongside
# the DB_ROOM_* family.
_RELATED_ROOM_CODES = frozenset(
    {
        "DB_DAO_SCOPE_UNRESOLVED",
        "DB_DAO_INHERITANCE_UNRESOLVED",
        "DB_DAO_INHERITANCE_INVALID_ANCESTOR",
        "DB_DAO_ANNOTATION_SCOPE_UNRESOLVED",
        "DB_SIGNATURE_UNRESOLVED",
        "DB_CALL_TARGET_AMBIGUOUS",
        "DB_METHOD_BODY_UNSUPPORTED",
        "INVENTORY_DURABILITY_UNCONFIRMED",
    }
)


def _room_inventory_catalog_codes() -> set[str]:
    """The catalog diagnostics the Room inventory document is required to
    enumerate: every ``DB_ROOM_*`` code plus the related infra codes the
    inventory can emit."""
    return {code for code in DIAGNOSTIC_CODES if code.startswith("DB_ROOM_")} | set(_RELATED_ROOM_CODES)


def _docs_table_codes(docs_path: Path) -> set[str]:
    """Codes enumerated in the section-3 infrastructure diagnostic table of
    ``docs/ci/DB_ROOM_INVENTORY.md``."""
    text = docs_path.read_text(encoding="utf-8")
    section = text.split("## 3.", 1)[1].split("\n## ", 1)[0]
    return {
        match.group(1)
        for match in re.finditer(r"^\| `([A-Z][A-Z0-9_]*)` \|", section, re.MULTILINE)
    }


def test_every_room_inventory_diagnostic_is_registered() -> None:
    """Keep the inventory emitter and canonical diagnostic catalog in lockstep."""
    root = Path(__file__).resolve().parent
    source_path = root / "db_guard" / "room_inventory.py"
    tree = ast.parse(source_path.read_text(encoding="utf-8"))
    emitted: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Name):
            continue
        if node.func.id != "_diag" or not node.args:
            continue
        argument = node.args[0]
        if isinstance(argument, ast.Constant) and isinstance(argument.value, str):
            emitted.add(argument.value)

    for node in ast.walk(tree):
        if (isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant)
                and isinstance(node.value.value, str)
                and any(isinstance(target, ast.Name) and target.id == "code"
                        for target in node.targets)):
            emitted.add(node.value.value)

    emitted.update({
        f"DB_ROOM_{code}" for code in (
            ERROR_INVALID_INPUT,
            ERROR_BAD_PATH,
            ERROR_UNSUPPORTED_DECLARATION,
            ERROR_AMBIGUOUS_DECLARATION,
            ERROR_MISSING_DECLARATION,
            ERROR_UNSUPPORTED_METHOD,
            ERROR_AMBIGUOUS_METHOD,
        )
    })
    # ERROR_ANNOTATION_SCOPE_UNRESOLVED is already fully-qualified
    # (DB_DAO_ANNOTATION_SCOPE_UNRESOLVED), so it is added as-is, never
    # DB_ROOM_-prefixed.
    emitted.add(ERROR_ANNOTATION_SCOPE_UNRESOLVED)
    assert emitted
    assert all(is_known_diagnostic(code) for code in emitted), sorted(
        code for code in emitted if not is_known_diagnostic(code)
    )


def test_docs_room_inventory_table_equals_catalog_code_set() -> None:
    """The docs table must enumerate exactly the catalog's Room-inventory
    diagnostic codes, in both directions, so a future catalog code cannot be
    silently omitted from the documentation (and a docs-only code cannot be
    invented)."""
    root = Path(__file__).resolve().parent
    docs_path = root.parent / "docs" / "ci" / "DB_ROOM_INVENTORY.md"
    docs_codes = _docs_table_codes(docs_path)
    catalog_codes = _room_inventory_catalog_codes()

    assert docs_codes == catalog_codes, (
        "docs table and catalog Room-inventory code set diverged; "
        f"missing from docs: {sorted(catalog_codes - docs_codes)}; "
        f"unregistered in docs: {sorted(docs_codes - catalog_codes)}"
    )
    assert all(is_known_diagnostic(code) for code in docs_codes), sorted(
        code for code in docs_codes if not is_known_diagnostic(code)
    )
