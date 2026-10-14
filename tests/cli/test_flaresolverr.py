"""Leaving the CLI stops the FlareSolverr container the installer started, so
the next ./Ejecutar_instalar.sh brings it up fresh."""
import pytest

import cli.core.flaresolverr as flaresolverr_module
from cli.core.config import Config, Ports, resolve_toolchain_paths
from cli.core.flaresolverr import CONTAINER, owned_marker, stop_flaresolverr


@pytest.fixture
def cfg(tmp_path):
    return Config(
        repo_root=tmp_path,
        tools=resolve_toolchain_paths(tmp_path),
        ports=Ports(),
    )


class FakeDocker:
    def __init__(self, *, present=True, fail=False):
        self.present = present
        self.fail = fail
        self.calls: list[list[str]] = []

    def __call__(self, argv, **kwargs):
        if not self.present:
            raise FileNotFoundError("docker")
        self.calls.append(list(argv))
        if self.fail:
            raise RuntimeError("No such container")
        return ""


def _mark_owned(cfg):
    marker = owned_marker(cfg)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.touch()
    return marker


def test_the_marker_path_matches_the_one_the_installer_writes(cfg):
    assert owned_marker(cfg) == cfg.repo_root / "_tools" / "flaresolverr.owned"


def test_stop_touches_nothing_when_the_installer_did_not_start_it(cfg):
    """A FlareSolverr the user runs on their own (the installer found 8191
    already answering) is not ours to stop."""
    docker = FakeDocker()

    stop_flaresolverr(cfg, runner=docker)

    assert docker.calls == []


def test_stop_stops_the_container_and_clears_the_marker(cfg):
    marker = _mark_owned(cfg)
    docker = FakeDocker()

    stop_flaresolverr(cfg, runner=docker)

    assert docker.calls == [["docker", "stop", CONTAINER]]
    assert not marker.exists()


@pytest.mark.parametrize("docker", [FakeDocker(fail=True), FakeDocker(present=False)])
def test_stop_is_quiet_when_docker_fails(cfg, docker):
    marker = _mark_owned(cfg)

    stop_flaresolverr(cfg, runner=docker)  # no levanta

    assert not marker.exists()


def test_a_test_path_that_reaches_real_docker_fails_loudly():
    with pytest.raises(RuntimeError) as exc:
        flaresolverr_module._run_docker(["docker", "version"])

    assert "test" in str(exc.value).lower()
