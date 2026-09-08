"""The LAN trust report: what a `start lan` run tells the operator once the
terminator is up. Pure — built entirely from a `Startup`, so it is testable
from a hand-built fixture and never imports `cli.core.lan_proxy` or
`cli.core.runtime_config` at runtime; both surfaces (`cli/plain/runner.py`,
`cli/tui/app.py`) call `render` on the exact `Startup` `apply_mode` returns,
so the two can never drift apart (design.md, Decision 4).

Content only, deliberately thin: it points at
`docs/LAN_HTTPS_SETUP.md` instead of restating it.
"""
from __future__ import annotations

from typing import TYPE_CHECKING, Optional
from urllib.parse import urlparse

if TYPE_CHECKING:
    from cli.core.runtime_config import Startup

DOC_POINTER = "Más detalle: docs/LAN_HTTPS_SETUP.md"
STOP_LINE = "`stop` baja el terminador TLS."
# Two screens, not one, and the order is load-bearing: the toggle in step 2
# does not exist until step 1 has run, so an operator sent straight to it finds
# an empty menu and concludes the download failed (`docs/LAN_HTTPS_SETUP.md` §4).
IOS_INSTALL_STEP = (
    "  iOS paso 1 — instalalo: Ajustes → «Perfil descargado» (arriba de todo) "
    "→ Instalar. Si no aparece: Ajustes → General → VPN y gestión de dispositivos."
)
IOS_TRUST_STEP = (
    "  iOS paso 2 — confiá en él: Ajustes → General → Información → "
    "Ajustes de confianza de certificados → activá el switch."
)


def render(startup: "Startup") -> Optional[str]:
    """The block to print after a `start` — `None` for `local`, which keeps
    its existing single-line summary untouched."""
    if startup.mode == "local":
        return None
    if startup.ca is not None:
        return _trusted(startup)
    return _self_signed(startup)


def _trusted(startup: "Startup") -> str:
    ca = startup.ca
    return "\n".join([
        f"Abrí esto en el dispositivo: {startup.origins.frontend}",
        f"iOS — instalá el certificado: {ca.ios}",
        f"Android — instalá el certificado: {ca.android}",
        IOS_INSTALL_STEP,
        IOS_TRUST_STEP,
        STOP_LINE,
        DOC_POINTER,
    ])


def _self_signed(startup: "Startup") -> str:
    # Ports come from the resolved origins, not a hardcoded constant, so a
    # SCRAPPY_*_ORIGIN tunnel still names the ports it actually serves on.
    front_port = urlparse(startup.origins.frontend).port
    back_port = urlparse(startup.origins.backend).port
    return "\n".join([
        f"Abrí esto en el dispositivo: {startup.origins.frontend}",
        "El navegador va a advertir: no hay una CA local instalada.",
        f"Aceptá la advertencia en los dos puertos: {front_port} y {back_port}.",
        STOP_LINE,
        DOC_POINTER,
    ])
