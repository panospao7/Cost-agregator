"""GR-13 mediation-analysis fixture sanity tests (stdlib os only)."""

import os

FIXTURES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")

GROUP_MANIFESTS = (
    "call_binding_manifest.yml",
    "helper_proof_manifest.yml",
    "worker_proof_manifest.yml",
    "protocol_manifest.yml",
)
CONSOLIDATED_MANIFEST = "expected_manifest.yml"
EXPECTED_CONSOLIDATED_ROWS = 64
FIELDS = ("id", "file", "expectedResolution", "expectedProof",
          "expectedRoot", "expectation")

RESOLUTIONS = frozenset((
    "EXACT_SYNCHRONOUS", "EXACT_CANONICAL_SCOPE", "AMBIGUOUS_TARGET",
    "UNRESOLVED_TARGET", "VIRTUAL_DISPATCH", "INTERFACE_DISPATCH",
    "FUNCTION_REFERENCE", "ESCAPING_LAMBDA", "ASYNC_DISPATCH",
    "RECURSIVE_UNSUPPORTED", "EXTERNAL_ENTRY", "UNSUPPORTED_SYNTAX",
))

PROOFS = frozenset((
    "PROVEN_HELPER", "PROVEN_WORKER_MEDIATED",
    "COUNTEREXAMPLE_UNGUARDED_CALL_PATH", "COUNTEREXAMPLE_NON_WORKER_ROOT",
    "COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE", "UNPROVEN_EXTERNAL_ENTRY",
    "UNPROVEN_AMBIGUOUS_CALL", "UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK",
    "UNPROVEN_RECURSION", "UNSUPPORTED_SOURCE", "INFRASTRUCTURE_FAILURE",
))

ROOTS = frozenset((
    "WORKER_DO_WORK", "FRAMEWORK_CALLBACK", "PUBLIC_OR_PROTECTED_EXTERNAL",
    "TOP_LEVEL_EXTERNAL", "CONSTRUCTOR_EXTERNAL", "UNKNOWN_EXTERNAL",
))


def parse_rows(path):
    """Parse a minimal manifest into rows of key/value string pairs."""
    rows, current = [], None
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("- "):
                current = {}
                rows.append(current)
                line = line[2:].strip()
            if current is None:
                continue
            for field in FIELDS:
                if line.startswith(field + ":"):
                    current[field] = line[len(field) + 1:].strip()
                    break
    return rows


def manifest_rows():
    """Yield (manifest name, row) pairs for every manifest in the corpus."""
    for name in GROUP_MANIFESTS + (CONSOLIDATED_MANIFEST,):
        for row in parse_rows(os.path.join(FIXTURES_DIR, name)):
            yield name, row


def test_manifests_exist():
    for name in GROUP_MANIFESTS + (CONSOLIDATED_MANIFEST,):
        assert os.path.isfile(os.path.join(FIXTURES_DIR, name)), name


def test_consolidated_manifest_has_52_rows():
    rows = parse_rows(os.path.join(FIXTURES_DIR, CONSOLIDATED_MANIFEST))
    assert len(rows) == EXPECTED_CONSOLIDATED_ROWS, len(rows)


def test_no_duplicate_ids():
    seen = set()
    for name, row in manifest_rows():
        row_id = row.get("id", "")
        assert row_id, name + ": row without id"
        assert (name, row_id) not in seen, name + ": duplicate id " + row_id
        seen.add((name, row_id))


def test_expected_values_use_controlled_vocabularies():
    for name, row in manifest_rows():
        if "expectedResolution" in row:
            assert row["expectedResolution"] in RESOLUTIONS, (name, row)
        if "expectedProof" in row:
            assert row["expectedProof"] in PROOFS, (name, row)
        if "expectedRoot" in row:
            assert row["expectedRoot"] in ROOTS, (name, row)


def test_referenced_files_exist():
    for name, row in manifest_rows():
        rel = row.get("file", "")
        assert rel, name + ": row without file"
        assert os.path.isfile(os.path.join(FIXTURES_DIR, rel)), rel


def main():
    for test in (
        test_manifests_exist,
        test_consolidated_manifest_has_52_rows,
        test_no_duplicate_ids,
        test_expected_values_use_controlled_vocabularies,
        test_referenced_files_exist,
    ):
        test()
        print("PASS:", test.__name__)
    print("All fixture checks passed.")


if __name__ == "__main__":
    main()
