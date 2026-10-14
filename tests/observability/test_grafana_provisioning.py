"""Grafana provisioning: the dashboard must point at the provisioned datasource,
and every panel query must run as `grafana_ro` against the real schema.

The DB half needs a migrated database (the dev one is fine):
    OBS_TEST_DSN=postgresql://postgres:postgres@localhost:5432/scraper
Everything runs inside one transaction that is rolled back, role included.
"""
import json
import os
import re
from pathlib import Path

import pytest
import yaml

RAIZ = Path(__file__).resolve().parents[2]
OBS = RAIZ / "observability"
DATASOURCE = OBS / "grafana/provisioning/datasources/scrappy-postgres.yml"
DASHBOARDS = sorted((OBS / "grafana/dashboards").glob("*.json"))
ALERTS = OBS / "grafana/provisioning/alerting/scrappy-alerts.yml"
ROLE_SQL = OBS / "postgres/grafana-readonly.sql"


def _datasource_uid():
    return yaml.safe_load(DATASOURCE.read_text(encoding="utf-8"))["datasources"][0]["uid"]


def _panels(dashboard):
    for panel in dashboard["panels"]:
        yield panel
        yield from panel.get("panels", [])


def _queries():
    for path in DASHBOARDS:
        dashboard = json.loads(path.read_text(encoding="utf-8"))
        for panel in _panels(dashboard):
            for target in panel.get("targets", []):
                yield pytest.param(target["rawSql"], id=f"{path.stem}:{panel['title']}")
        for var in dashboard.get("templating", {}).get("list", []):
            if var.get("type") == "query":
                yield pytest.param(var["query"], id=f"{path.stem}:${var['name']}")
    alerts = yaml.safe_load(ALERTS.read_text(encoding="utf-8"))
    for group in alerts["groups"]:
        for rule in group["rules"]:
            for data in rule["data"]:
                if "rawSql" in data["model"]:
                    yield pytest.param(data["model"]["rawSql"], id=f"alert:{rule['title']}")


def test_hay_al_menos_un_dashboard():
    assert DASHBOARDS


@pytest.mark.parametrize("path", DASHBOARDS, ids=lambda p: p.stem)
def test_todo_panel_usa_el_datasource_provisionado(path):
    uid = _datasource_uid()
    dashboard = json.loads(path.read_text(encoding="utf-8"))
    sin_ds = [
        p["title"]
        for p in _panels(dashboard)
        if p.get("type") != "row" and p.get("datasource", {}).get("uid") != uid
    ]
    assert sin_ds == []


@pytest.mark.parametrize("path", DASHBOARDS, ids=lambda p: p.stem)
def test_ids_de_panel_unicos(path):
    ids = [p["id"] for p in _panels(json.loads(path.read_text(encoding="utf-8")))]
    assert len(ids) == len(set(ids))


def test_las_alertas_usan_el_datasource_provisionado():
    uid = _datasource_uid()
    alerts = yaml.safe_load(ALERTS.read_text(encoding="utf-8"))
    for group in alerts["groups"]:
        for rule in group["rules"]:
            sql = [d for d in rule["data"] if "rawSql" in d["model"]]
            assert sql and all(d["datasourceUid"] == uid for d in sql), rule["title"]


# ─── DB half ─────────────────────────────────────────────────────────────

DSN = os.environ.get("OBS_TEST_DSN")
needs_db = pytest.mark.skipif(not DSN, reason="OBS_TEST_DSN no seteada")


def _expand_macros(sql, sitios="'fullh4rd'"):
    """What Grafana's postgres plugin does before sending the query."""
    rango = "now() - interval '30 days' AND now()"
    sql = re.sub(r"\$__timeFilter\(([^)]+)\)", rf"\1 BETWEEN {rango}", sql)
    sql = sql.replace("$__timeFrom()", "(now() - interval '30 days')")
    sql = sql.replace("$__timeTo()", "now()")
    sql = re.sub(r"\$__interval_ms\b", "3600000", sql)
    sql = re.sub(r"\$\{?sitio(:sqlstring)?\}?", sitios, sql)
    assert "$" not in sql, f"macro sin expandir: {sql}"
    return sql


@pytest.fixture
def ro_cursor():
    psycopg2 = pytest.importorskip("psycopg2")
    conn = psycopg2.connect(DSN)
    try:
        cur = conn.cursor()
        cur.execute(ROLE_SQL.read_text(encoding="utf-8"))
        cur.execute("SET ROLE grafana_ro")
        yield cur
    finally:
        conn.rollback()
        conn.close()


@needs_db
@pytest.mark.parametrize("sql", list(_queries()))
def test_la_query_corre_como_grafana_ro(ro_cursor, sql):
    ro_cursor.execute(_expand_macros(sql))
    ro_cursor.fetchall()


@needs_db
@pytest.mark.parametrize("tabla", ["usuario", "refresh_token", "password_reset_token"])
def test_grafana_ro_no_lee_datos_personales(ro_cursor, tabla):
    import psycopg2

    ro_cursor.execute("SELECT to_regclass(%s)", (tabla,))
    if ro_cursor.fetchone()[0] is None:
        pytest.skip(f"{tabla} no existe en este esquema")
    with pytest.raises(psycopg2.errors.InsufficientPrivilege):
        ro_cursor.execute(f"SELECT 1 FROM {tabla} LIMIT 1")


@needs_db
def test_grafana_ro_no_escribe(ro_cursor):
    import psycopg2

    with pytest.raises((psycopg2.errors.InsufficientPrivilege, psycopg2.errors.ReadOnlySqlTransaction)):
        ro_cursor.execute("UPDATE sitio SET es_premium = es_premium")


# ─── A truncated site (DONE + error) must not look healthy ───────────────

def _alert_sql(uid):
    alerts = yaml.safe_load(ALERTS.read_text(encoding="utf-8"))
    rule = next(r for g in alerts["groups"] for r in g["rules"] if r["uid"] == uid)
    return next(d["model"]["rawSql"] for d in rule["data"] if "rawSql" in d["model"])


def _panel_sql(title):
    dashboard = json.loads(DASHBOARDS[0].read_text(encoding="utf-8"))
    panel = next(p for p in _panels(dashboard) if p["title"] == title)
    return panel["targets"][0]["rawSql"]


@pytest.fixture
def run_cortada():
    """A newer run where vaypol came back DONE but cut mid-pagination."""
    psycopg2 = pytest.importorskip("psycopg2")
    conn = psycopg2.connect(DSN)
    try:
        cur = conn.cursor()
        cur.execute(ROLE_SQL.read_text(encoding="utf-8"))
        cur.execute("""
            WITH r AS (
              INSERT INTO scrape_run (scrape_uuid, started_at, finished_at, status)
              VALUES (gen_random_uuid(), now(), now(), 'COMPLETED') RETURNING id)
            INSERT INTO scrape_run_site (scrape_run_id, sitio_key, status, productos_count,
                                         started_at, finished_at, error)
            SELECT id, 'vaypol', 'DONE', 3480, now(), now(),
                   'paginación cortada en p59: net::ERR_NETWORK_CHANGED' FROM r""")
        cur.execute("SET ROLE grafana_ro")
        yield cur
    finally:
        conn.rollback()
        conn.close()


@needs_db
def test_la_alerta_dispara_con_un_sitio_cortado(run_cortada):
    run_cortada.execute(_alert_sql("scrappy-sitio-caido"))
    assert dict(run_cortada.fetchall())["vaypol"] == 1


@needs_db
def test_el_panel_de_estado_muestra_el_sitio_cortado(run_cortada):
    run_cortada.execute(_expand_macros(_panel_sql("Estado por sitio (última vez que corrió)"), "'vaypol'"))
    filas = {f[0]: f[1] for f in run_cortada.fetchall()}
    assert filas["vaypol"] == "PARTIAL"


@needs_db
def test_fallas_lista_el_sitio_cortado(run_cortada):
    run_cortada.execute(_expand_macros(_panel_sql("Fallas en el rango"), "'vaypol'"))
    assert any(f[1] == "vaypol" and f[2] == "PARTIAL" for f in run_cortada.fetchall())
