"""`LanProxyService` is the injectable face of the `lan_proxy` module functions:
it binds the Docker runner once and satisfies `runtime_config`'s `proxy=` duck type."""
import pytest

from cli.core import lan_proxy
from cli.core.config import Config, Ports, resolve_toolchain_paths
from cli.core.lan_proxy import LanProxyService


@pytest.fixture
def cfg(tmp_path):
    return Config(
        repo_root=tmp_path,
        tools=resolve_toolchain_paths(tmp_path),
        ports=Ports(),
    )


class FakeDocker:
    def __init__(self):
        self.calls: list[list[str]] = []

    def __call__(self, argv, **kwargs):
        self.calls.append(list(argv))
        return ""


def test_start_then_stop_run_through_the_bound_runner(cfg):
    docker = FakeDocker()
    service = LanProxyService(runner=docker)

    service.start_proxy(cfg, "192.0.2.10")
    service.stop_proxy(cfg)

    assert [c[1] for c in docker.calls][-1] == "rm"
    assert any(c[1] == "run" for c in docker.calls)


def test_stop_without_a_started_proxy_touches_no_container(cfg):
    docker = FakeDocker()

    LanProxyService(runner=docker).stop_proxy(cfg)

    assert docker.calls == []


def test_the_other_methods_delegate_to_the_module_functions(cfg, monkeypatch):
    monkeypatch.setattr(lan_proxy, "preflight", lambda: "pre")
    monkeypatch.setattr(lan_proxy, "detect_lan_ip", lambda: "192.0.2.10")
    monkeypatch.setattr(lan_proxy, "ensure_cert", lambda c, ip: (c, ip))
    service = LanProxyService()

    assert service.preflight() == "pre"
    assert service.detect_lan_ip() == "192.0.2.10"
    assert service.ensure_cert(cfg, "192.0.2.10") == (cfg, "192.0.2.10")
