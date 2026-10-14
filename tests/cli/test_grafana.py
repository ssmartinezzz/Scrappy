"""The installer brings Grafana up only for users who opted in (the env file
exists); the CLI shows how to reach it and stops it on quit. All values here
are fakes: the real credentials never enter a fixture."""
import pytest

from cli.core.config import Config, Ports, resolve_toolchain_paths
from cli.core.grafana import GrafanaInfo, GrafanaService, describe, owned_marker, read_info, stop_grafana


@pytest.fixture
def cfg(tmp_path):
    return Config(
        repo_root=tmp_path,
        tools=resolve_toolchain_paths(tmp_path),
        ports=Ports(),
    )


class FakeRunner:
    def __init__(self, *, fail=False):
        self.fail = fail
        self.calls: list[list[str]] = []

    def __call__(self, argv, **kwargs):
        self.calls.append(list(argv))
        if self.fail:
            raise FileNotFoundError("docker")
        return ""


def _env_file(cfg, text):
    path = cfg.repo_root / "observability" / "grafana.env"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def _mark_owned(cfg):
    marker = owned_marker(cfg)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.touch()
    return marker


def test_the_marker_path_matches_the_one_the_installer_writes(cfg):
    assert owned_marker(cfg) == cfg.repo_root / "_tools" / "grafana.owned"


def test_info_is_none_when_the_user_never_opted_in(cfg):
    assert read_info(cfg) is None


def test_info_reads_url_user_and_password_from_the_env_file(cfg):
    _env_file(cfg, "# comment\n\nGRAFANA_ADMIN_PASSWORD=fake-pass\nGRAFANA_PORT=3100\n")

    info = read_info(cfg)

    assert info.url == "http://localhost:3100"
    assert info.user == "admin"
    assert info.password == "fake-pass"


@pytest.mark.parametrize("host_line", [
    "GRAFANA_BIND_HOST=", "GRAFANA_BIND_HOST=0.0.0.0", "GRAFANA_BIND_HOST=127.0.0.1",
    "GRAFANA_BIND_HOST=localhost", "",
])
def test_info_points_at_localhost_when_grafana_listens_on_this_machine(cfg, host_line):
    _env_file(cfg, f"GRAFANA_ADMIN_PASSWORD=x\n{host_line}\n")

    assert read_info(cfg).url == "http://localhost:3001"


def test_info_builds_no_url_for_a_specific_lan_address(cfg):
    """A plain-HTTP URL to another host is not something the CLI hands out;
    the user who bound it there knows where it is."""
    _env_file(cfg, "GRAFANA_ADMIN_PASSWORD=x\nGRAFANA_BIND_HOST=192.168.1.5\n")

    info = read_info(cfg)

    assert info.url is None
    assert info.bind_host == "192.168.1.5"


def test_stop_touches_nothing_when_the_installer_did_not_start_it(cfg):
    runner = FakeRunner()

    stop_grafana(cfg, runner=runner)

    assert runner.calls == []


def test_stop_runs_the_observability_down_and_clears_the_marker(cfg):
    marker = _mark_owned(cfg)
    runner = FakeRunner()

    stop_grafana(cfg, runner=runner)

    assert runner.calls == [["bash", str(cfg.repo_root / "scripts" / "observability.sh"), "down"]]
    assert not marker.exists()


def test_stop_is_quiet_when_docker_is_gone(cfg):
    marker = _mark_owned(cfg)

    stop_grafana(cfg, runner=FakeRunner(fail=True))  # no levanta

    assert not marker.exists()


def test_the_service_exposes_info_and_stops_through_its_runner(cfg):
    _env_file(cfg, "GRAFANA_ADMIN_PASSWORD=x\n")
    marker = _mark_owned(cfg)
    runner = FakeRunner()
    service = GrafanaService(runner=runner)

    assert service.info(cfg).password == "x"
    service.stop_grafana(cfg)

    assert len(runner.calls) == 1
    assert not marker.exists()


def test_describe_shows_url_user_and_password():
    info = GrafanaInfo(url="http://localhost:3001", user="admin", password="fake-pass")

    assert describe(info) == "Grafana: http://localhost:3001  usuario: admin  clave: fake-pass"


def test_describe_points_at_the_env_file_when_bound_to_a_lan_address():
    info = GrafanaInfo(url=None, user="admin", password="fake-pass", bind_host="192.168.1.5")

    assert describe(info) == (
        "Grafana: atado a 192.168.1.5 (GRAFANA_BIND_HOST en observability/grafana.env)"
        "  usuario: admin  clave: fake-pass"
    )


def test_describe_tells_how_to_opt_in_when_there_is_no_info():
    assert "scripts/observability.sh up" in describe(None)
