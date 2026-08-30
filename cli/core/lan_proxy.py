"""TLS terminator for the `lan` mode, owned by the CLI.

The backend serves plain HTTP and will keep doing so: TLS is terminated in
front of it, the same shape a real deployment has. This module owns that
terminator for local use — certificate, nginx config, and container lifecycle —
so `start lan` needs no side script and no exported variables.

Why a proxy at all: the refresh cookie is `Secure`, and browsers exempt
`localhost` from that rule but not a private IP. Over plain `http://<ip>` a
phone drops the cookie silently and session recovery never works.
"""
from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import socket
import subprocess
from typing import Callable, Optional, Sequence

from cli.core.config import Config

CONTAINER = "scrappy-lan-tls"
IMAGE = "nginx:1.27-alpine"

TLS_FRONTEND_PORT = 8443
TLS_BACKEND_PORT = 8444
CA_PORT = 8081

Runner = Callable[..., object]


class ProxyUnavailable(RuntimeError):
    """The proxy cannot run — no Docker, or the container refused to start."""


@dataclass(frozen=True)
class CertBundle:
    cert: Path
    key: Path
    ca_pem: Optional[Path]
    ca_der: Optional[Path]
    #: True when a local CA signed it, so the phone can be made to trust it
    #: instead of being told to click through its own warning.
    trusted: bool


def state_dir(cfg: Config) -> Path:
    path = cfg.repo_root / "_tools" / "lan-proxy"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _run_docker(argv: Sequence[str], **kwargs) -> str:
    return subprocess.run(
        list(argv), check=True, capture_output=True, text=True, **kwargs
    ).stdout


def detect_lan_ip() -> str:
    """The address other devices on the network can reach.

    Uses a UDP socket to a public address: it sends nothing, it only makes the
    kernel pick the interface it would route through. Enumerating interfaces
    instead would have to guess among docker0/lxcbr0/virbr0, none of which is
    reachable from a phone.
    """
    override = os.environ.get("SCRAPPY_LAN_IP", "").strip()
    if override:
        return override

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("1.1.1.1", 53))
        return sock.getsockname()[0]
    finally:
        sock.close()


def ensure_cert(cfg: Config, ip: str) -> CertBundle:
    """A certificate valid for `ip`, signed by a local CA when `mkcert` is
    installed. Falls back to self-signed, which every browser correctly
    distrusts — usable, but the device warns on each port."""
    state = state_dir(cfg)
    cert, key = state / "lan.crt", state / "lan.key"

    if shutil.which("mkcert"):
        subprocess.run(
            ["mkcert", "-cert-file", str(cert), "-key-file", str(key),
             ip, "localhost", "127.0.0.1"],
            check=True, capture_output=True, text=True,
        )
        caroot = Path(
            subprocess.run(["mkcert", "-CAROOT"], check=True,
                           capture_output=True, text=True).stdout.strip()
        )
        ca_pem = state / "rootCA.pem"
        shutil.copy(caroot / "rootCA.pem", ca_pem)
        # iOS will not open a PEM: Safari only offers to install a profile for
        # DER content served under a .cer URL. Android takes either.
        ca_der = state / "scrappy-dev-ca.cer"
        subprocess.run(
            ["openssl", "x509", "-in", str(ca_pem), "-outform", "der",
             "-out", str(ca_der)],
            check=True, capture_output=True, text=True,
        )
        return CertBundle(cert=cert, key=key, ca_pem=ca_pem, ca_der=ca_der, trusted=True)

    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "365",
         "-keyout", str(key), "-out", str(cert), "-subj", f"/CN={ip}",
         "-addext", f"subjectAltName=IP:{ip},DNS:localhost,IP:127.0.0.1"],
        check=True, capture_output=True, text=True,
    )
    return CertBundle(cert=cert, key=key, ca_pem=None, ca_der=None, trusted=False)


def nginx_conf(cfg: Config, *, tls_frontend: int, tls_backend: int, ca_port: int) -> str:
    """The proxy config.

    `X-Forwarded-Proto` is what makes the backend see the request as secure;
    without it `isSecure()` stays false behind the proxy and the `Secure`
    cookie never sticks. `error_page 497` turns nginx's "plain HTTP sent to an
    HTTPS port" into a redirect — typing a bare IP makes the browser try
    `http://` first, and the raw 400 says nothing useful on a phone.
    """
    return f"""events {{}}
http {{
  proxy_http_version 1.1;
  proxy_set_header Host              $host;
  proxy_set_header X-Real-IP         $remote_addr;
  proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto https;
  proxy_set_header X-Forwarded-Host  $host;
  proxy_set_header Upgrade           $http_upgrade;
  proxy_set_header Connection        "upgrade";
  client_max_body_size 25m;

  server {{
    listen {ca_port};
    location = /rootCA.pem {{ alias /certs/rootCA.pem; default_type application/x-x509-ca-cert; }}
    location = /scrappy-dev-ca.cer {{ alias /certs/scrappy-dev-ca.cer; default_type application/x-x509-ca-cert; }}
    location / {{ return 404; }}
  }}

  server {{
    listen {tls_frontend} ssl;
    ssl_certificate     /certs/lan.crt;
    ssl_certificate_key /certs/lan.key;
    error_page 497 =301 https://$host:{tls_frontend}$request_uri;
    location / {{ proxy_pass http://127.0.0.1:{cfg.ports.frontend}; }}
  }}

  server {{
    listen {tls_backend} ssl;
    ssl_certificate     /certs/lan.crt;
    ssl_certificate_key /certs/lan.key;
    error_page 497 =301 https://$host:{tls_backend}$request_uri;
    location / {{ proxy_pass http://127.0.0.1:{cfg.ports.backend}; }}
  }}
}}
"""


def start_proxy(
    cfg: Config,
    ip: str,
    *,
    tls_frontend: int = TLS_FRONTEND_PORT,
    tls_backend: int = TLS_BACKEND_PORT,
    ca_port: int = CA_PORT,
    runner: Runner = _run_docker,
) -> None:
    """Replace any previous container and start the terminator.

    `--network host` is required, not a convenience: the backend only trusts
    `X-Forwarded-*` from loopback, and on a bridge network the peer is a
    `172.x` address, so every forwarded header would be discarded and the
    failure would look exactly like plain HTTP.
    """
    state = state_dir(cfg)
    (state / "nginx.conf").write_text(
        nginx_conf(cfg, tls_frontend=tls_frontend, tls_backend=tls_backend,
                   ca_port=ca_port),
        encoding="utf-8",
    )

    try:
        runner(["docker", "rm", "-f", CONTAINER])
    except FileNotFoundError as exc:
        raise ProxyUnavailable(
            "el modo 'lan' necesita Docker para el terminador TLS y no lo encontré. "
            "Instalalo, o usá 'start' (local) que no lo necesita."
        ) from exc
    except Exception:
        pass  # no había contenedor previo

    try:
        runner([
            "docker", "run", "-d", "--name", CONTAINER, "--network", "host",
            "--restart", "no",
            "-v", f"{state / 'nginx.conf'}:/etc/nginx/nginx.conf:ro",
            "-v", f"{state}:/certs:ro",
            IMAGE,
        ])
    except FileNotFoundError as exc:
        raise ProxyUnavailable(
            "el modo 'lan' necesita Docker para el terminador TLS y no lo encontré. "
            "Instalalo, o usá 'start' (local) que no lo necesita."
        ) from exc
    except Exception as exc:
        raise ProxyUnavailable(f"el terminador TLS no arrancó: {exc}") from exc


def stop_proxy(cfg: Config, *, runner: Runner = _run_docker) -> None:
    """Best-effort: `stop` runs this unconditionally, and not having a proxy up
    is the normal case, not an error worth reporting.

    Does nothing unless this working tree actually started one. Reaching for
    Docker on every `stop` would mean the test suite — which exercises `stop` —
    removes a container on the developer's machine, and it would also let one
    checkout kill a proxy another one is using.
    """
    if not (state_dir(cfg) / "nginx.conf").is_file():
        return
    try:
        runner(["docker", "rm", "-f", CONTAINER])
    except Exception:
        pass
