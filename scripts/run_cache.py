"""Deterministic per-run caches for the DB guard's pure text analyses.

PR-GR-10c performance budget: the static guards repeat the same expensive
pure functions many times per run -- the evidence verifier re-reads,
re-masks, and re-parses the same file per callable group (~200 groups over
~300 files), the D4 scanner re-masks the same source per declaration and
per call site, and the migrate/coverage builders re-read and re-mask the
whole tree across stages.  This module is the ONE shared cache seam for
those operations.

Determinism contract (PR-GR-10c): every cache is keyed by exact content
identity --

* ``file_text`` keys by the normalized absolute path plus the file's
  ``(st_mtime_ns, st_size)`` stamp, so a file changed within a run is
  re-read on the next access (re-read after write) and a cache hit returns
  byte-identical text to a fresh read (same UTF-8 text-mode universal
  newlines the callers used before);
* the parser analyses (``cached_value`` consumers in
  ``kotlin_callable_parser``) key by the exact input text -- plus the owner
  identity, the tolerance flag, and the project type index's CONTENT digest
  for callable discovery -- so a hit is byte-identical to recomputation by
  construction (every wrapped function is a pure function of its inputs).

No diagnostic, finding, or report byte can change: a cache hit always
returns what recomputation would have returned, and a failed computation is
never cached (the exception propagates exactly as before).

Memory bounds: caches live for the process lifetime (one guard run / one
pytest session), never persist to disk, and each namespace is capped --
inserting beyond the cap clears that cache first (deterministic reset).
``clear_run_caches()`` empties everything for embedders that reuse one
process across runs.  Single-threaded use only (each guard runs one scan
per process; pytest is single-threaded by default).
"""
from __future__ import annotations

import hashlib
import json
import os

__all__ = [
    "cached_value",
    "file_text",
    "clear_run_caches",
    "cache_stats",
    "project_types_digest",
]


# Per-namespace entry caps.  The caps bound memory deterministically (the
# real production tree is ~1e3 Kotlin files, so the file-text cache holds at
# most one copy of the tree and the mask cache at most two; the owner and
# callable entries are small per-declaration structures).  Inserting beyond
# the cap clears the namespace first, which keeps the reset deterministic
# instead of evicting by an order-dependent policy.
_CACHE_LIMITS = {
    "file_text": 4096,
    "mask": 8192,
    "owners": 8192,
    "callables": 4096,
    "project_types": 8192,
    "nested_types": 8192,
}

_CACHES: dict[str, dict] = {}


def _cache(name: str) -> dict:
    cache = _CACHES.get(name)
    if cache is None:
        cache = _CACHES[name] = {}
    return cache


def cached_value(name: str, key, compute):
    """Return the cached value for ``key``, computing it on a miss.

    ``compute`` must be a zero-argument callable producing the value for
    ``key``.  A raised exception is never cached -- it propagates to the
    caller exactly like the uncached computation.  Values are shared across
    callers, so every cached value must be immutable (the parser caches
    hold strings and tuples of frozen dataclasses only).
    """
    cache = _cache(name)
    try:
        return cache[key]
    except KeyError:
        pass
    result = compute()
    limit = _CACHE_LIMITS.get(name)
    if limit is not None and len(cache) >= limit:
        cache.clear()
    cache[key] = result
    return result


def _file_cache_key(abs_path: str) -> str:
    """Normalized cache key for one absolute path.

    Purely lexical normalization (separator + case folding on Windows) so
    the same file reached through different spellings -- ``os.path.join``
    with POSIX-relative policy paths versus ``str(Path(...) / ...)`` --
    shares one entry.  ``normpath``/``normcase`` never touch the filesystem,
    so the key cannot change which file is read.
    """
    try:
        return os.path.normcase(os.path.normpath(abs_path))
    except (TypeError, ValueError):
        return abs_path


def _file_stamp(path: str):
    """``(st_mtime_ns, st_size)`` of ``path``, or ``None`` when unstattable.

    An unstattable path skips the cache entirely so the caller's own
    ``open`` failure (and its controlled diagnostic) decides, exactly like
    the uncached read.
    """
    try:
        stat = os.stat(path)
    except OSError:
        return None
    return (stat.st_mtime_ns, stat.st_size)


def file_text(abs_path: str) -> str:
    """Read ``abs_path`` as UTF-8 text through the per-run file cache.

    Byte-identical to ``open(abs_path, "r", encoding="utf-8").read()`` /
    ``Path.read_text(encoding="utf-8")`` (both use UTF-8 text mode with
    universal newlines).  The stamp ``(st_mtime_ns, st_size)`` is taken
    BEFORE the read and validated on every access, so a file written after
    a cached read is re-read on the next access (re-read after write).

    Exceptions are never cached: ``OSError`` and ``UnicodeDecodeError``
    propagate to the caller's existing handling (each consumer keeps its
    own controlled diagnostic contract).
    """
    key = _file_cache_key(abs_path)
    stamp = _file_stamp(key)
    cache = _cache("file_text")
    if stamp is not None:
        cached = cache.get(key)
        if cached is not None and cached[0] == stamp:
            return cached[1]
    with open(abs_path, "r", encoding="utf-8") as handle:
        text = handle.read()
    if stamp is not None:
        if len(cache) >= _CACHE_LIMITS["file_text"]:
            cache.clear()
        cache[key] = (stamp, text)
    return text


def project_types_digest(project_types):
    """Stable CONTENT key for a project type index (lazily memoized).

    Callable discovery depends on the index's content, and one process can
    build several equal-content indexes (one per scan/verification run), so
    the callable cache keys on a digest of the index CONTENT -- not on
    object identity.  The digest is computed once per index object and
    stored on it (``object.__setattr__``; the frozen dataclass ignores the
    attribute for equality), so equal-content indexes share cache entries
    and different-content indexes can never collide.  ``None`` (no index)
    maps to ``None`` so the pure single-file closed world keeps its own key.
    """
    if project_types is None:
        return None
    digest = getattr(project_types, "_run_cache_content_digest", None)
    if isinstance(digest, str):
        return digest
    canonical = json.dumps(
        {
            "by_simple_name": [
                [name, list(fqcns)]
                for name, fqcns in sorted(project_types.by_simple_name.items())
            ],
            "qualified": sorted(project_types.qualified),
        },
        separators=(",", ":"),
    )
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    try:
        object.__setattr__(project_types, "_run_cache_content_digest", digest)
    except (AttributeError, TypeError):
        # A non-assignable index type just recomputes the digest per call:
        # slower, never wrong.
        pass
    return digest


def clear_run_caches() -> None:
    """Empty every per-run cache (embedders that reuse one process)."""
    _CACHES.clear()


def cache_stats() -> dict:
    """Entry counts per cache namespace (bounded diagnostics only)."""
    return {name: len(cache) for name, cache in sorted(_CACHES.items())}
