"""Neutral guardrail library package.

The production source-scope authority (PR-GR-10B Slice 1) lives in
``scripts.guardrails.production_source_scope``.  DB-layer consumers reach
the same one live implementation through the
``scripts.db_guard.source_roots`` compatibility re-export shim; new guards
import this package directly.
"""
