"""Protocol-v2 reporting helpers for the database discovery guard."""
from __future__ import annotations

from pathlib import Path

from ..ci.guard_findings import GuardRunReport, write_report_atomic as _write_report_atomic


def write_db_report_atomic(path, report: GuardRunReport) -> None:
    """Persist a validated v2 report using the shared atomic writer."""
    if not isinstance(report, GuardRunReport):
        raise TypeError("report must be a GuardRunReport")
    _write_report_atomic(Path(path), report)


__all__ = ["write_db_report_atomic"]
