"""Leaving the CLI stops the FlareSolverr container the installer started, and
`start` brings it back, so a relaunch without the installer still has it."""
import pytest

import cli.core.flaresolverr as flaresolverr_module
from cli.core.config import Config, Ports, resolve_toolchain_paths
from cli.core.flaresolverr import (
    CONTAINER,
    FlareSolverrService,
    ensure_flaresolverr,
    owned_marker,
    stop_flaresolverr,
)


@pytest.fixture
def cfg(tmp_path):
    return Config(
        repo_root=tmp_path,
        tools=resolve_toolchain_paths(tmp_path),
        ports=Ports(),
    )


class FakeDocker:
    def __init__(self, *, present=True, fail=False, running="", fail_start=False):
        self.present = present
        self.fail = fail
        self.running = running
        self.fail_start = fail_start
        self.calls: list[list[str]] = []

    def __call__(self, argv, **kwargs):
        if not self.present:
            raise FileNotFoundError("docker")
        self.calls.append(list(argv))
        if self.fail:
            raise RuntimeError("No such container")
        if argv[1] == "inspect":
            return self.running + "\n"
        if argv[1] == "start" and self.fail_start:
            raise RuntimeError("port is already allocated")
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


def test_the_service_stops_through_the_runner_it_was_built_with(cfg):
    marker = _mark_owned(cfg)
    docker = FakeDocker()

    FlareSolverrService(runner=docker).stop_flaresolverr(cfg)

    assert docker.calls == [["docker", "stop", CONTAINER]]
    assert not marker.exists()


INSPECT = ["docker", "inspect", "-f", "{{.State.Running}}", CONTAINER]


def test_ensure_starts_a_stopped_container_and_marks_it_owned(cfg):
    """The 2026-11-21 failure: a CLI quit stopped it, the next `start` did
    not bring it back, and fullh4rd ran against a dead solver."""
    docker = FakeDocker(running="false")

    line = ensure_flaresolverr(cfg, runner=docker)

    assert docker.calls == [INSPECT, ["docker", "start", CONTAINER]]
    assert owned_marker(cfg).is_file(), "the next quit must stop what start started"
    assert "arrancado" in line


def test_ensure_leaves_a_running_container_alone(cfg):
    docker = FakeDocker(running="true")

    assert ensure_flaresolverr(cfg, runner=docker) is None
    assert docker.calls == [INSPECT]
    assert not owned_marker(cfg).exists(), "a solver we did not start is not ours to stop"


@pytest.mark.parametrize("docker", [FakeDocker(fail=True), FakeDocker(present=False)])
def test_ensure_does_nothing_without_docker_or_container(cfg, docker):
    assert ensure_flaresolverr(cfg, runner=docker) is None
    assert not owned_marker(cfg).exists()


def test_ensure_reports_a_failed_start_without_raising(cfg):
    docker = FakeDocker(running="false", fail_start=True)

    line = ensure_flaresolverr(cfg, runner=docker)

    assert "fullh4rd" in line
    assert not owned_marker(cfg).exists()


def test_the_service_ensures_through_the_runner_it_was_built_with(cfg):
    docker = FakeDocker(running="false")

    FlareSolverrService(runner=docker).ensure_flaresolverr(cfg)

    assert docker.calls[-1] == ["docker", "start", CONTAINER]
