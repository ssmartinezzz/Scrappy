"""`lan_report.render` — the LAN success report. Pure: built entirely from
a hand-built `Startup`, no Docker, no mkcert, no real proxy."""
from __future__ import annotations

from cli.core.lan_proxy import CaUrls
from cli.core.lan_report import render
from cli.core.runtime_config import LAN, LOCAL, Origins, Startup

FRONTEND = "https://192.0.2.10:8443"
BACKEND = "https://192.0.2.10:8444"


def _startup(*, mode: str, ca) -> Startup:
    return Startup(mode=mode, origins=Origins(frontend=FRONTEND, backend=BACKEND), ca=ca)


def test_local_mode_has_no_report():
    assert render(_startup(mode=LOCAL, ca=None)) is None


def test_trusted_lan_orders_frontend_then_ios_then_android_urls_then_trust_step_then_stop_then_doc_pointer_last():
    ca = CaUrls(
        ios="http://192.0.2.10:8081/scrappy-dev-ca.cer",
        android="http://192.0.2.10:8081/rootCA.pem",
    )
    startup = _startup(mode=LAN, ca=ca)

    report = render(startup)

    assert report is not None
    assert report.index(FRONTEND) < report.index(ca.ios) < report.index(ca.android)
    assert "Ajustes" in report and "Ajustes de confianza de certificados" in report
    assert report.index(ca.android) < report.index("Ajustes")
    assert "stop" in report
    assert report.index("Ajustes") < report.index("stop")
    last_line = report.splitlines()[-1]
    assert last_line.endswith("docs/LAN_HTTPS_SETUP.md")


def test_trusted_lan_names_where_the_profile_is_installed_not_only_where_it_is_trusted():
    """iOS needs TWO settings screens, and the toggle in the second one does
    not exist until the first is done — `docs/LAN_HTTPS_SETUP.md` §4 spells
    that out. A report that names only the trust screen sends the operator to
    an empty menu, which is the exact dead end this report exists to prevent.
    """
    ca = CaUrls(
        ios="http://192.0.2.10:8081/scrappy-dev-ca.cer",
        android="http://192.0.2.10:8081/rootCA.pem",
    )

    report = render(_startup(mode=LAN, ca=ca))

    assert "Perfil descargado" in report
    assert "VPN y gestión de dispositivos" in report
    # Install before trust: the order is not cosmetic, step 2 is inert without it.
    assert report.index("Perfil descargado") < report.index(
        "Ajustes de confianza de certificados"
    )


def test_self_signed_lan_warns_once_then_names_both_ports_then_stop_then_doc_pointer_and_omits_any_ca_url():
    startup = _startup(mode=LAN, ca=None)

    report = render(startup)

    assert report is not None
    warning_idx = report.index("advertir")
    assert report.index(FRONTEND) < warning_idx
    assert "8443" in report and "8444" in report
    ports_idx = max(report.index("8443"), report.index("8444"))
    assert warning_idx < ports_idx
    stop_idx = report.index("stop")
    assert ports_idx < stop_idx
    last_line = report.splitlines()[-1]
    assert last_line.endswith("docs/LAN_HTTPS_SETUP.md")
    assert stop_idx < report.index(last_line)
    # No CA URL at all: with no CA there is nothing behind them.
    assert "8081" not in report
    assert ".cer" not in report and "rootCA.pem" not in report
