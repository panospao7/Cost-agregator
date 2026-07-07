"""
test_verify_pii_logging_boundaries.py
MIT-003 acceptance tests for the PII Logging Boundary Guard.

Run with: python -m pytest scripts/test_verify_pii_logging_boundaries.py -v
"""
import os
import sys
import tempfile
from pathlib import Path

# Import the module under test directly
sys.path.insert(0, os.path.dirname(__file__))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard",
    os.path.join(os.path.dirname(__file__), "verify_pii_logging_boundaries.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

scan_file = _mod.scan_file
load_allowlist = _mod.load_allowlist
is_allowlisted = _mod.is_allowlisted


# ── Helpers ─────────────────────────────────────────────────────────────────

def _write_kt(tmp_path, filename: str, content: str) -> Path:
    """Write a Kotlin file and return its path."""
    f = tmp_path / filename
    f.write_text(content, encoding="utf-8")
    return f


def _make_allowlist(tmp_path, yaml_content: str) -> Path:
    """Write a YAML allowlist and return its path."""
    p = tmp_path / "pii_logging_allowlist.yml"
    p.write_text(yaml_content, encoding="utf-8")
    return p


# ── Test 1: Log with rawOcrText fails ──────────────────────────────────────

def test_log_with_raw_ocr_text_detected(tmp_path):
    """Log.d with rawOcrText interpolation → violation."""
    kt_file = _write_kt(
        tmp_path,
        "BadLogger.kt",
        """package com.example

import android.util.Log

class BadLogger {
    companion object {
        private const val TAG = "BadLogger"
    }

    fun processReceipt(rawOcrText: String) {
        Log.d(TAG, "Processing raw OCR: $rawOcrText")
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for Log.d with rawOcrText, got: {violations}"
    )
    assert any("rawOcrText" in v or "rawocrtext" in v.lower() for v in violations), (
        f"Violation should mention rawOcrText, got: {violations}"
    )


# ── Test 2: Log with sanitized data passes ─────────────────────────────────

def test_log_with_sanitized_data_passes(tmp_path):
    """Log.d with sanitized, non-sensitive fields → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "SafeLogger.kt",
        """package com.example

import android.util.Log

class SafeLogger {
    companion object {
        private const val TAG = "SafeLogger"
    }

    fun logExpense(category: String, amount: Double, merchantName: String) {
        Log.d(TAG, "Expense: category=$category, amount=$amount, merchant=$merchantName")
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for sanitized log, got: {violations}"
    )


# ── Test 3: Exception with user data fails ─────────────────────────────────

def test_exception_with_user_data_detected(tmp_path):
    """throw Exception with user.email → violation."""
    kt_file = _write_kt(
        tmp_path,
        "BadErrorHandler.kt",
        """package com.example

class BadErrorHandler {
    fun authenticate(user: User) {
        if (!user.isValid) {
            throw IllegalStateException("Failed to authenticate ${user.email}")
        }
    }
}

data class User(val email: String, val isValid: Boolean)
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for exception with user.email, got: {violations}"
    )
    assert any(
        "email" in v.lower() for v in violations
    ), (
        f"Violation should mention email user data, got: {violations}"
    )


# ── Test 4: Debug-only path passes (BuildConfig.DEBUG guarded) ─────────────

def test_debug_guarded_file_path_passes(tmp_path):
    """filePath logged inside BuildConfig.DEBUG guard → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "DebugPathLogger.kt",
        """package com.example

import android.util.Log
import com.example.BuildConfig

class DebugPathLogger {
    companion object {
        private const val TAG = "DebugPath"
    }

    fun writeReceiptFile(filePath: String) {
        val data = loadReceiptData()

        // Only log file path in debug builds
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Writing receipt to $filePath")
        }

        // Actually write the file
        writeToDisk(filePath, data)
    }

    private fun loadReceiptData(): ByteArray = byteArrayOf()
    private fun writeToDisk(path: String, data: ByteArray) {}
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for filePath guarded by BuildConfig.DEBUG, "
        f"got: {violations}"
    )


# ── Test 5: e.message in log without debug guard fails ─────────────────────

def test_raw_exception_message_in_log_detected(tmp_path):
    """Log.e(TAG, e.message) → violation."""
    kt_file = _write_kt(
        tmp_path,
        "ExMessageLogger.kt",
        """package com.example

import android.util.Log

class ExMessageLogger {
    companion object {
        private const val TAG = "ExMsg"
    }

    fun processData(data: String) {
        try {
            parseData(data)
        } catch (e: Exception) {
            Log.e(TAG, e.message)
        }
    }

    private fun parseData(data: String) {}
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for Log.e with e.message, got: {violations}"
    )
    assert any(
        "e.message" in v or "raw exception" in v.lower() for v in violations
    ), (
        f"Violation should mention e.message, got: {violations}"
    )


# ── Test 6: printStackTrace in production code fails ───────────────────────

def test_print_stack_trace_detected(tmp_path):
    """e.printStackTrace() in non-test code → violation."""
    kt_file = _write_kt(
        tmp_path,
        "StackTracePrinter.kt",
        """package com.example

class StackTracePrinter {
    fun handleError() {
        try {
            riskyOperation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun riskyOperation() {}
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for printStackTrace(), got: {violations}"
    )
    assert any(
        "printStackTrace" in v for v in violations
    ), (
        f"Violation should mention printStackTrace, got: {violations}"
    )


# ── Test 7: Allowlisted file is skipped ────────────────────────────────────

def test_allowlisted_file_skipped(tmp_path):
    """File allowlisted with symbol='*' → all violations suppressed."""
    kt_file = _write_kt(
        tmp_path,
        "PrivacyGuard.kt",
        """package com.example

object PrivacyGuard {
    fun sanitize(rawOcrText: String): String {
        // This intentionally references rawOcrText in a log-like context
        // but is allowlisted because it's a privacy enforcement utility.
        println("Sanitizing OCR text of length: ${rawOcrText.length}")
        return rawOcrText.replace(Regex("[0-9]{16}"), "****")
    }
}
""",
    )

    yaml_content = """- rule: G-PII-01
  path: PrivacyGuard.kt
  symbol: "*"
  reason: "Privacy enforcement utility — needs to inspect PII"
  owner: "@panospao7"
  expires: "permanent"
  linked_issue: "MIT-003"
"""
    allowlist = load_allowlist(
        _make_allowlist(tmp_path, yaml_content)
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for allowlisted file PrivacyGuard.kt, "
        f"got: {violations}"
    )


# ── Test 8: StackTrace variable in log detected ────────────────────────────

def test_stack_trace_variable_in_log_detected(tmp_path):
    """Log.e with stackTrace variable → violation."""
    kt_file = _write_kt(
        tmp_path,
        "StackTraceLogger.kt",
        """package com.example

import android.util.Log

class StackTraceLogger {
    companion object {
        private const val TAG = "StackLog"
    }

    fun logError(e: Exception) {
        val stackTrace = e.stackTraceToString()
        Log.e(TAG, "Error occurred: $stackTrace")
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for Log.e with stackTrace variable, got: {violations}"
    )
    assert any(
        "stackTrace" in v.lower() or "stacktrace" in v.lower()
        for v in violations
    ), (
        f"Violation should mention stackTrace, got: {violations}"
    )


# ── Test 9: Exception(message = e.message) chaining detected ───────────────

def test_exception_wrapping_raw_message_detected(tmp_path):
    """new Exception with e.message → violation."""
    kt_file = _write_kt(
        tmp_path,
        "ExWrap.kt",
        """package com.example

class ExWrap {
    fun wrapError(e: Exception) {
        try {
            doWork()
        } catch (e: Exception) {
            throw RuntimeException(e.message)
        }
    }

    private fun doWork() {}
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for RuntimeException with e.message, got: {violations}"
    )
    assert any(
        "e.message" in v or "raw exception" in v.lower() for v in violations
    ), (
        f"Violation should mention e.message, got: {violations}"
    )


# ── Test 10: Comment-only sensitive references pass ────────────────────────

def test_comment_only_references_pass(tmp_path):
    """Sensitive variable names in comments → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "CommentedLogger.kt",
        """package com.example

import android.util.Log

class CommentedLogger {
    companion object {
        private const val TAG = "Safe"
    }

    fun process(result: String) {
        // NOTE: We no longer log rawOcrText here. Use sanitized version below.
        Log.d(TAG, "Processed result: $result")

        // Historical: was Log.e(TAG, "OCR: $rawOcrText") — removed for PII safety
        // Also removed: throw IllegalStateException("bad ${user.email}")
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for comment-only references, got: {violations}"
    )


# ── Test: scan_file returns list type ──────────────────────────────────────

def test_scan_returns_list():
    """scan_file always returns a list type for violations."""
    violations, fatal = scan_file(
        Path("nonexistent.kt"),
        [],
    )
    assert fatal, "Non-existent file should cause fatal error"
    assert isinstance(violations, list)
