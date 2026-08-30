"""Origin modes: the launcher picks where the frontend talks, per run."""
import re

import pytest

from cli.core.config import Config, Ports, resolve_toolchain_paths
from cli.core.runtime_config import (
    MODES,
    apply_mode,
    UnknownMode,
    resolve_origins,
    write_runtime_config,
)


@pytest.fixture
def cfg(tmp_path):
    return Config(
        repo_root=tmp_path,
        tools=resolve_toolchain_paths(tmp_path),
        ports=Ports(),
    )


def test_local_mode_targets_loopback(cfg):
    origins = resolve_origins("local", cfg)

    assert origins.backend == f"http://localhost:{cfg.ports.backend}"
    assert origins.frontend == f"http://localhost:{cfg.ports.frontend}"


def test_lan_mode_reads_the_env_overrides(cfg, monkeypatch):
    monkeypatch.setenv("SCRAPPY_BACKEND_ORIGIN", "https://192.0.2.10:8444")
    monkeypatch.setenv("SCRAPPY_FRONTEND_ORIGIN", "https://192.0.2.10:8443")

    origins = resolve_origins("lan", cfg)

    assert origins.backend == "https://192.0.2.10:8444"
    assert origins.frontend == "https://192.0.2.10:8443"


def test_lan_mode_without_overrides_fails_loudly(cfg, monkeypatch):
    """Silently falling back to localhost would serve a bundle that calls the
    phone itself — the failure this whole mechanism exists to prevent."""
    monkeypatch.delenv("SCRAPPY_BACKEND_ORIGIN", raising=False)
    monkeypatch.delenv("SCRAPPY_FRONTEND_ORIGIN", raising=False)

    with pytest.raises(UnknownMode) as exc:
        resolve_origins("lan", cfg)

    assert "SCRAPPY_BACKEND_ORIGIN" in str(exc.value)


def test_an_unknown_mode_names_the_valid_ones(cfg):
    with pytest.raises(UnknownMode) as exc:
        resolve_origins("produccion", cfg)

    for mode in MODES:
        assert mode in str(exc.value)


def test_writing_the_config_leaves_a_loadable_assignment(cfg):
    dist = cfg.repo_root / "frontend" / "dist"
    dist.mkdir(parents=True)

    path = write_runtime_config(cfg, "https://192.0.2.10:8444")

    assert path == dist / "config.js"
    assert path.read_text(encoding="utf-8").strip().endswith(
        "window.__API_BASE__ = 'https://192.0.2.10:8444';"
    )


def test_writing_the_config_escapes_quotes(cfg):
    """The origin reaches this from the environment, so it is not trusted to be
    a bare URL: an unescaped quote would break out of the assignment."""
    dist = cfg.repo_root / "frontend" / "dist"
    dist.mkdir(parents=True)

    path = write_runtime_config(cfg, "https://x.test'; evil()//")

    written = path.read_text(encoding="utf-8").strip()

    # Exact form, not a substring heuristic: every quote inside the value must
    # be backslash-escaped, so the payload stays inert data instead of closing
    # the assignment and running.
    assert written.endswith(
        r"window.__API_BASE__ = 'https://x.test\'; evil()//';"
    )
    assert re.search(r"(?<!\\)'", written[written.index("=") + 3 : -2]) is None


def test_writing_without_a_dist_is_a_no_op(cfg):
    assert write_runtime_config(cfg, "http://localhost:3000") is None


def test_apply_mode_wires_open_url_cors_and_the_bundle(cfg, monkeypatch):
    monkeypatch.setenv("SCRAPPY_FRONTEND_ORIGIN", "https://192.0.2.10:8443")
    monkeypatch.setenv("SCRAPPY_BACKEND_ORIGIN", "https://192.0.2.10:8444")
    (cfg.repo_root / "frontend" / "dist").mkdir(parents=True)
    env = {"APP_CORS_ALLOWED_ORIGINS": "http://localhost:5173"}

    origins = apply_mode(cfg, "lan", env)

    assert origins.frontend == "https://192.0.2.10:8443"
    # The browser must land where this run actually serves, not where the
    # .env was frozen at install time.
    assert env["APP_OPEN_URL"] == "https://192.0.2.10:8443"
    # Added, not replaced: losing localhost would break the same machine.
    assert env["APP_CORS_ALLOWED_ORIGINS"] == (
        "http://localhost:5173,https://192.0.2.10:8443"
    )
    assert "https://192.0.2.10:8444" in (
        cfg.repo_root / "frontend" / "dist" / "config.js"
    ).read_text(encoding="utf-8")


def test_apply_mode_does_not_duplicate_an_origin_already_allowed(cfg):
    (cfg.repo_root / "frontend" / "dist").mkdir(parents=True)
    env = {"APP_CORS_ALLOWED_ORIGINS": f"http://localhost:{cfg.ports.frontend}"}

    apply_mode(cfg, "local", env)

    assert env["APP_CORS_ALLOWED_ORIGINS"] == f"http://localhost:{cfg.ports.frontend}"
