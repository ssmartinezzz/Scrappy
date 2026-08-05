"""Tests for cli.core.logs — service log paths + a bounded file tail.

These back the "servers must never write to our TTY" fix: every child
process gets a file here, and the console reads it back on demand instead
of letting the child scribble over the rendered frame.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from cli.core.config import Config, Ports, ToolchainPaths
from cli.core.logs import SERVICES, service_log_path, tail


def _cfg(repo_root: Path) -> Config:
    tools = ToolchainPaths(
        jdk21=repo_root / "_tools" / "jdk21",
        maven=repo_root / "_tools" / "maven",
        node=repo_root / "_tools" / "node",
        cli_venv=repo_root / "_tools" / "cli-venv",
        pgsql=repo_root / "_tools" / "pgsql",
    )
    return Config(repo_root=repo_root, tools=tools, ports=Ports())


def test_service_log_path_lives_under_scraper_logs(tmp_path: Path):
    path = service_log_path(_cfg(tmp_path), "backend")
    assert path == tmp_path / "scraper" / "logs" / "backend.log"


def test_service_log_path_rejects_unknown_service(tmp_path: Path):
    """The service name reaches a filesystem path, so it is validated
    against a fixed allow-list rather than interpolated blindly."""
    with pytest.raises(ValueError):
        service_log_path(_cfg(tmp_path), "../../etc/passwd")


def test_every_known_service_has_a_path(tmp_path: Path):
    cfg = _cfg(tmp_path)
    for service in SERVICES:
        assert service_log_path(cfg, service).name == f"{service}.log"


def test_tail_returns_empty_list_when_file_is_absent(tmp_path: Path):
    assert tail(tmp_path / "nope.log") == []


def test_tail_returns_last_n_lines_in_order(tmp_path: Path):
    path = tmp_path / "a.log"
    path.write_text("\n".join(f"line {i}" for i in range(100)) + "\n", encoding="utf-8")
    assert tail(path, lines=3) == ["line 97", "line 98", "line 99"]


def test_tail_returns_whole_file_when_shorter_than_requested(tmp_path: Path):
    path = tmp_path / "a.log"
    path.write_text("only\ntwo\n", encoding="utf-8")
    assert tail(path, lines=50) == ["only", "two"]


def test_tail_reads_from_the_end_without_loading_the_whole_file(tmp_path: Path):
    """A backend log rolls to megabytes; the tail must stay bounded. The
    seek-from-end read is asserted by size, not by timing: we never read
    more than a bounded window even when the file is far larger."""
    path = tmp_path / "big.log"
    with path.open("w", encoding="utf-8") as fh:
        for i in range(200_000):
            fh.write(f"row {i}\n")

    reads: list[int] = []
    real_open = Path.open

    class _CountingFile:
        def __init__(self, fh):
            self._fh = fh

        def read(self, size=-1):
            data = self._fh.read(size)
            reads.append(len(data))
            return data

        def __getattr__(self, name):
            return getattr(self._fh, name)

        def __enter__(self):
            self._fh.__enter__()
            return self

        def __exit__(self, *exc):
            return self._fh.__exit__(*exc)

    def _patched_open(self, *args, **kwargs):
        return _CountingFile(real_open(self, *args, **kwargs))

    Path.open = _patched_open
    try:
        out = tail(path, lines=5)
    finally:
        Path.open = real_open

    assert out == [f"row {i}" for i in range(199_995, 200_000)]
    assert sum(reads) < 200_000, "tail read the whole file instead of seeking to the end"


def test_tail_tolerates_undecodable_bytes(tmp_path: Path):
    """Child processes emit whatever they emit; a bad byte must not raise
    inside the console render path."""
    path = tmp_path / "a.log"
    path.write_bytes(b"good line\n\xff\xfe broken\n")
    assert len(tail(path)) == 2


def test_tail_ignores_a_trailing_newline(tmp_path: Path):
    path = tmp_path / "a.log"
    path.write_text("one\ntwo\n", encoding="utf-8")
    assert tail(path, lines=10) == ["one", "two"]
