"""
test_verify_di_release_boundaries.py
Acceptance tests for the DI/Release Binding Guard (G-DI-01).

4 test cases:
  1. @Provides returning Mock type — FAILS
  2. @Provides with BuildConfig.DEBUG guard — PASSES
  3. http:// URL in DI module — FAILS
  4. Allowlisted file — PASSES

Run with: python -m pytest scripts/test_verify_di_release_boundaries.py -v
"""
import os
import sys
import tempfile

# Import the module under test directly
sys.path.insert(0, os.path.dirname(__file__))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard",
    os.path.join(os.path.dirname(__file__), "verify_di_release_boundaries.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

scan_di_file = _mod.scan_di_file
scan_gradle_file = _mod.scan_gradle_file
load_allowlist = _mod.load_allowlist
is_allowlisted = _mod.is_allowlisted
RULE_ID = _mod.RULE_ID
PROJECT_ROOT = _mod.PROJECT_ROOT


# ── Helpers ─────────────────────────────────────────────────────────────────

def _write_kt(tmp_path, filename, content):
    """Write a .kt file in the given directory and return its path."""
    filepath = os.path.join(str(tmp_path), filename)
    os.makedirs(os.path.dirname(filepath) or str(tmp_path), exist_ok=True)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)
    return filepath


def _write_gradle(tmp_path, filename, content):
    """Write a build.gradle.kts file."""
    filepath = os.path.join(str(tmp_path), filename)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)
    return filepath


def _make_allowlist(tmp_path, yaml_content):
    """Write a YAML allowlist and return its path."""
    p = os.path.join(str(tmp_path), "di_release_allowlist.yml")
    with open(p, "w", encoding="utf-8") as f:
        f.write(yaml_content)
    return p


# Monkey-patch PROJECT_ROOT so rel_path calculations work from tmp_path
def _patch_root(tmp_path_str):
    _mod.PROJECT_ROOT = tmp_path_str


# ── Test 1: @Provides returning Mock type FAILS ──────────────────────────────

def test_provides_mock_type_fails(tmp_path, monkeypatch):
    """@Provides returning MockSomething without BuildConfig.DEBUG → violation."""
    monkeypatch.setattr(_mod, "PROJECT_ROOT", str(tmp_path))

    kt_file = _write_kt(
        tmp_path,
        "MockDiModule.kt",
        """package com.example.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.example.data.MockExpenseRepository
import com.example.domain.ExpenseRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MockDiModule {

    @Provides
    @Singleton
    fun provideExpenseRepository(): ExpenseRepository {
        return MockExpenseRepository()
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_di_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for @Provides returning Mock type, got: {violations}"
    )
    assert any("Mock" in v for v in violations), (
        f"Violation message should mention Mock, got: {violations}"
    )


# ── Test 2: @Provides with BuildConfig.DEBUG guard PASSES ────────────────────

def test_provides_mock_with_buildconfig_passes(tmp_path, monkeypatch):
    """@Provides returning MockSomething guarded by BuildConfig.DEBUG → no violation."""
    monkeypatch.setattr(_mod, "PROJECT_ROOT", str(tmp_path))

    kt_file = _write_kt(
        tmp_path,
        "GuardedDiModule.kt",
        """package com.example.di

import com.yourname.expensetracker.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.example.data.MockExpenseRepository
import com.example.domain.ExpenseRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GuardedDiModule {

    @Provides
    @Singleton
    fun provideExpenseRepository(): ExpenseRepository {
        return if (BuildConfig.DEBUG) {
            MockExpenseRepository()
        } else {
            RealExpenseRepository()
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_di_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for BuildConfig.DEBUG-guarded Mock, got: {violations}"
    )


# ── Test 3: http:// URL in DI module FAILS ──────────────────────────────────

def test_http_url_in_di_module_fails(tmp_path, monkeypatch):
    """http:// URL in DI module → violation."""
    monkeypatch.setattr(_mod, "PROJECT_ROOT", str(tmp_path))

    kt_file = _write_kt(
        tmp_path,
        "UrlDiModule.kt",
        """package com.example.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UrlDiModule {

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient {
        val baseUrl = "http://api.example.com/v1"
        return OkHttpClient.Builder().build()
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_di_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for http:// URL, got: {violations}"
    )
    assert any("http://" in v or "non-SSL" in v for v in violations), (
        f"Violation message should mention http:// or non-SSL, got: {violations}"
    )


# ── Test 4: Allowlisted file PASSES ─────────────────────────────────────────

def test_allowlisted_file_passes(tmp_path, monkeypatch):
    """Mock type in allowlisted file → no violation."""
    monkeypatch.setattr(_mod, "PROJECT_ROOT", str(tmp_path))

    kt_file = _write_kt(
        tmp_path,
        "DebugExpenseRepository.kt",
        """package com.example.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.example.data.MockExpenseRepository
import com.example.domain.ExpenseRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DebugDiModule {

    @Provides
    @Singleton
    fun provideExpenseRepository(): ExpenseRepository {
        return MockExpenseRepository()
    }
}
""",
    )

    allowlist_content = f"""\
- rule: {RULE_ID}
  path: DebugExpenseRepository.kt
  symbol: "*"
  reason: "Debug-only repository — guarded by BuildConfig.DEBUG at callsite"
  owner: "@panospao7"
  expires: "permanent"
  linked_issue: "MIT-003"
"""
    allowlist = load_allowlist(
        _make_allowlist(tmp_path, allowlist_content)
    )
    violations, fatal = scan_di_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for allowlisted file, got: {violations}"
    )


# ── Test: isMinifyEnabled false in release FAILS ─────────────────────────────

def test_isMinifyEnabled_false_in_release_fails(tmp_path, monkeypatch):
    """isMinifyEnabled = false in release block → violation."""
    monkeypatch.setattr(_mod, "PROJECT_ROOT", str(tmp_path))

    gradle_file = _write_gradle(
        tmp_path,
        "build.gradle.kts",
        """plugins { id("com.android.application") }

android {
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_gradle_file(gradle_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for isMinifyEnabled=false in release, got: {violations}"
    )
    assert any("isMinifyEnabled" in v for v in violations), (
        f"Violation message should mention isMinifyEnabled, got: {violations}"
    )
