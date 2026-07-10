# Guard Implementation Policy

## Fail-closed rules

Every guard script MUST fail closed. The following are fatal errors (exit code 2):

1. A missing configured allowlist is a fatal error
2. A malformed allowlist is a fatal error
3. An unreadable source file is a fatal error
4. PyYAML not installed when allowlist is configured is a fatal error
5. An empty or missing source tree is a fatal error
6. A guard that cannot execute must never exit 0
7. A guard that cannot parse its input must never exit 0

## Path matching rules

1. Allowlist path matching uses suffix matching only: filepath must END with allowlist path
2. Bidirectional matching (A.endswith(B) OR B.endswith(A)) is forbidden
3. Directory-level path matching is exact, not substring
4. Symbol matching must compare the exact violation symbol
5. Wildcard symbol matching (symbol: "*") is forbidden after 2026-10-01

## Exit code rules

- 0: all checks passed, no violations
- 1: violations found (with --fail-on-violation)
- 2: infrastructure error (missing allowlist, bad config, cannot continue)

## Allowlist rules

1. Every entry must have: rule, path, symbol, reason, owner
2. Expiry must be YYYY-MM-DD format or linked-issue milestone
3. Permanent expiry ("permanent") is forbidden
4. Linked issue (linked_issue) is required
5. Wildcard symbol ("*") is forbidden after grace period (2026-10-01)

## Testing rules

1. Every guard must have a corresponding test file
2. Every guard must test: positive cases (pass), negative cases (fail)
3. Every guard should test: missing allowlist (exit 2), malformed allowlist (exit 2)
4. Allowlist compliance is tested by verify_allowlist_compliance.py
5. New guards follow the template at scripts/guard_template.py

## CI rules

1. All blocking guards must pass for CI to succeed
2. Warning guards with ratchet baselines block on new violations
3. Guard output is always captured and uploaded as artifacts
4. Guard suite runner runs all guards regardless of failures
5. Grace periods for policy changes are communicated in advance
