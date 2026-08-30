"""The CLI owns the LAN proxy: cert, nginx and lifecycle, no side script."""
import pytest

from cli.core.config import Config, Ports, resolve_toolchain_paths
from cli.core.lan_proxy import (
    ProxyUnavailable,
    detect_lan_ip,
    ensure_cert,
    nginx_conf,
    start_proxy,
    stop_proxy,
)


@pytest.fixture
def cfg(tmp_path):
    return Config(
        repo_root=tmp_path,
        tools=resolve_toolchain_paths(tmp_path),
        ports=Ports(),
    )


class FakeDocker:
    """Records argv instead of running it."""

    def __init__(self, *, present=True, failures=()):
        self.present = present
        self.failures = set(failures)
        self.calls: list[list[str]] = []

    def __call__(self, argv, **kwargs):
        if not self.present:
            raise FileNotFoundError("docker")
        self.calls.append(list(argv))
        if argv[1] in self.failures:
            raise RuntimeError(f"docker {argv[1]} falló")
        return ""


def test_detect_lan_ip_skips_loopback():
    ip = detect_lan_ip()

    assert ip and not ip.startswith("127.")


def test_the_conf_routes_each_tls_port_at_its_service(cfg):
    conf = nginx_conf(cfg, tls_frontend=8443, tls_backend=8444, ca_port=8081)

    assert f"proxy_pass http://127.0.0.1:{cfg.ports.frontend};" in conf
    assert f"proxy_pass http://127.0.0.1:{cfg.ports.backend};" in conf


def test_the_conf_forwards_the_headers_the_backend_needs(cfg):
    """Without X-Forwarded-Proto the backend never sees the request as secure,
    and the Secure refresh cookie is dropped by the browser."""
    conf = nginx_conf(cfg, tls_frontend=8443, tls_backend=8444, ca_port=8081)

    assert "proxy_set_header X-Forwarded-Proto https;" in conf
    assert "proxy_set_header X-Forwarded-For" in conf


def test_the_conf_redirects_plain_http_on_the_tls_ports(cfg):
    """Typing a bare IP makes the browser try http:// first; without this it
    dead-ends on a 400 that says nothing on a phone."""
    conf = nginx_conf(cfg, tls_frontend=8443, tls_backend=8444, ca_port=8081)

    assert conf.count("error_page 497") == 2


def test_the_ca_port_serves_only_the_ca(cfg):
    conf = nginx_conf(cfg, tls_frontend=8443, tls_backend=8444, ca_port=8081)

    assert "location / { return 404; }" in conf


def test_start_runs_the_proxy_on_the_host_network(cfg):
    """A bridge network makes the peer a 172.x address, and the backend only
    trusts forwarded headers from loopback — every header would be discarded."""
    docker = FakeDocker()
    (cfg.repo_root / "_tools" / "lan-proxy").mkdir(parents=True)

    start_proxy(cfg, ip="192.0.2.10", runner=docker)

    run = next(c for c in docker.calls if c[1] == "run")
    assert "--network" in run and run[run.index("--network") + 1] == "host"


def test_start_replaces_a_previous_container(cfg):
    docker = FakeDocker()
    (cfg.repo_root / "_tools" / "lan-proxy").mkdir(parents=True)

    start_proxy(cfg, ip="192.0.2.10", runner=docker)

    assert [c[1] for c in docker.calls][:1] == ["rm"]


def test_without_docker_it_says_so_instead_of_failing_obscurely(cfg):
    docker = FakeDocker(present=False)

    with pytest.raises(ProxyUnavailable) as exc:
        start_proxy(cfg, ip="192.0.2.10", runner=docker)

    assert "Docker" in str(exc.value)


def test_stop_is_quiet_when_nothing_is_running(cfg):
    docker = FakeDocker(failures={"rm"})

    stop_proxy(cfg, runner=docker)  # no levanta


def test_ensure_cert_writes_a_cert_and_key(cfg):
    state = cfg.repo_root / "_tools" / "lan-proxy"

    bundle = ensure_cert(cfg, ip="192.0.2.10")

    assert bundle.cert.is_file() and bundle.key.is_file()
    assert bundle.cert.parent == state
