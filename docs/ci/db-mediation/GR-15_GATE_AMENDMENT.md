# GR-15 GATE AMENDMENT — the owner-accepted tier (owner-sanctioned)

FINAL20 flagged this as "a gate-semantics decision that belongs to the
owner, not to this campaign."  The owner made that decision on
2026-09-14 (see `GR-14_OWNER_ACCEPTANCE_RECORD.md` for the decision
chain).  This document defines the amended gate semantics that GR-15
implements as its first batch.

## AMENDED SEMANTICS

GR-15's completion gate — FINAL20's `STILL UNPROVEN: 0` — is amended
to:

> Every active policy mutation row must end in exactly one of:
> `proven_helper`, `proven_worker_mediated`, `proven_restore_internal`,
> or `owner_accepted`.
>
> `owner_accepted` requires the row to be listed, with its caveats, in
> `docs/ci/db-mediation/GR-14_OWNER_ACCEPTANCE_RECORD.md` (the
> acceptance registry).  A row absent from the registry is unproven —
> fail closed.
>
> Counterexample tolerance remains ZERO and absolute (unchanged).  One
> counterexample is a HARD STOP exactly as before; no acceptance tier
> can absorb one.
>
> Infrastructure failures remain their own exit class (unchanged) and
> never count as acceptance or proof.

## ARITHMETIC AT THE AMENDED GATE

Final GR-14 board `03acbb044721e9dc` (379 entries): 344 +
14 + 1 proven = 359 proven rows; + 20 owner-accepted rows
(Families A-F = 18 + the ambiguous pair = 2); 0 unproven; 0
counterexamples.  (The interim "floor 19" figure is superseded — see
addendum Correction 3.)

## MANDATORY GATE OUTPUT CAVEAT

Wherever the gate or a board report counts accepted rows, the output
MUST carry the mask caveat so the clean number is never misread as
"everything is proven safe":

> "owner_accepted" means proven OR honestly accepted with recorded
> reasons.  Two of the accepted rows (the Handle pair) are known to
> mask counterexamples that surface under any future engine typing
> improvement — see the acceptance record's caveats 1 and 2.

## IMPLEMENTATION (GR-15 batch 1 — not part of GR-14)

The CI mechanics are deliberately NOT implemented in GR-14 (GR-15 was
gated on GR-14 completion; it must not start early).  GR-15 batch 1:

1. Give `scripts/ci/inspect_db_mediation_proof.py` an acceptance-
   registry input: it parses
   `docs/ci/db-mediation/GR-14_OWNER_ACCEPTANCE_RECORD.md` (or a yml
   projection of it — prefer yml if a generation input pattern exists;
   do NOT hand-edit `config/guards/db_ownership_policy.yml`), matches
   registered mutation keys against board rows, and reports unmatched
   registry entries as failures (the registry can never silently
   outlive its rows).
2. Add the `owner_accepted` bucket to the board/report output with the
   caveat line above.
3. Adjust exit semantics: exit 0 iff every row is proven-or-registered
   and counterexamples = 0; exit 1 unchanged otherwise; exit 2
   unchanged.
4. Tests + fixtures per the established admission pattern (closed set,
   pin test, delta attributable row-by-row); verification loop as in
   `GR-14_HANDOFF.md` section 4.

Until batch 1 lands, the current gate semantics remain in force and
the acceptance record is the authoritative evidence of the endpoint.

## WHAT THIS AMENDMENT DOES NOT DO

- It does not waive the counterexample HARD STOP.
- It does not accept any row not in the registry.
- It does not mark the masked pair "safe" — it records honest
  uncertainty over deliberate design (DDL-81-07), with the caveats
  carried verbatim from the acceptance record.
