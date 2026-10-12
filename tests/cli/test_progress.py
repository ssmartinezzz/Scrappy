"""Tests for cli.core.progress — the scrape view model built from the `/api/status`
and `/api/events` payloads, and its plain-text rendering."""
from __future__ import annotations

from datetime import datetime, timezone

from cli.core.progress import ScrapeView, render_lines, site_line, summary_line


def _site(nombre, estado, count=0, durMs=0, error=None):
    site = {"nombre": nombre, "estado": estado, "count": count, "durMs": durMs}
    if error:
        site["error"] = error
    return site


def _progreso(*sitios, total=None, productos=0):
    done = sum(1 for s in sitios if s["estado"] in ("done", "error"))
    return {"total": total if total is not None else len(sitios), "completados": done,
            "productos": productos, "sitios": list(sitios)}


class _Clock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


def _running(progreso=None, clock=None):
    view = ScrapeView(clock=clock or _Clock())
    view.apply("status", {"status": "RUNNING", "mensaje": "", "progreso": progreso,
                          "run": {"uuid": "u", "startedAt": "2026-10-12T10:00:00Z"}})
    return view


def test_percent_is_terminal_sites_over_total_not_time():
    clock = _Clock()
    view = _running(_progreso(_site("a", "done", 5), _site("b", "error"), _site("c", "en_curso"),
                              _site("d", "esperando"), total=4), clock)
    clock.t += 3600  # an hour passes: the number must not move

    assert view.percent == 50
    assert view.terminal == 2 and view.total == 4


def test_skipped_counts_as_terminal():
    view = _running(_progreso(_site("a", "skipped"), _site("b", "esperando")))

    assert view.percent == 50


def test_percent_is_zero_with_no_sites_and_no_progreso():
    assert _running(None).percent == 0
    assert _running(_progreso(total=0)).percent == 0


def test_percent_goes_down_when_a_new_run_starts():
    view = _running(_progreso(_site("a", "done", 1), total=1))
    assert view.percent == 100
    view.apply("scrape.status", {"status": "DONE", "mensaje": "fin"})
    view.apply("scrape.status", {"status": "RUNNING", "mensaje": "otra"})

    assert view.percent == 0 and view.progreso is None


def test_progress_event_replaces_the_progreso():
    view = _running(None)
    view.apply("scrape.progress", _progreso(_site("a", "done", 7), productos=7))

    assert view.productos == 7 and view.terminal == 1


def test_running_property_follows_status():
    view = _running(None)
    assert view.running
    view.apply("scrape.status", {"status": "DONE", "mensaje": ""})
    assert not view.running


def test_summary_line_shows_counts_products_and_elapsed():
    view = _running(_progreso(_site("a", "done", 5), _site("b", "esperando"), productos=5))
    now = datetime(2026, 10, 12, 10, 3, 5, tzinfo=timezone.utc)

    line = summary_line(view, now=now)

    assert "50%" in line and "1/2 sitios" in line and "5 productos" in line
    assert "3m05s" in line and "RUNNING" in line


def test_summary_line_falls_back_to_the_local_clock_without_started_at():
    clock = _Clock()
    view = ScrapeView(clock=clock)
    view.apply("scrape.status", {"status": "RUNNING", "mensaje": ""})
    clock.t += 65

    assert "1m05s" in summary_line(view, now=None)


def test_site_line_blanks_the_count_while_running():
    view = _running(_progreso(_site("vaypol", "en_curso", count=0, durMs=0)))

    row = view.rows()[0]

    assert row.count is None
    assert "vaypol" in site_line(row) and "en_curso" in site_line(row)


def test_site_line_shows_count_and_duration_of_a_finished_site():
    view = _running(_progreso(_site("freres", "done", count=412, durMs=31200)))

    line = site_line(view.rows()[0])

    assert "412" in line and "31.2s" in line


def test_running_site_without_duration_is_labelled_with_time_since_first_seen():
    clock = _Clock()
    view = _running(_progreso(_site("a", "en_curso")), clock)
    clock.t += 12
    view.apply("scrape.progress", _progreso(_site("a", "en_curso")))

    assert "hace 12s" in site_line(view.rows()[0])


def test_first_seen_is_forgotten_when_the_site_leaves_en_curso():
    clock = _Clock()
    view = _running(_progreso(_site("a", "en_curso")), clock)
    view.apply("scrape.progress", _progreso(_site("a", "done", 3, durMs=1000)))
    clock.t += 50
    view.apply("scrape.progress", _progreso(_site("a", "en_curso")))

    assert "hace 0s" in site_line(view.rows()[0])


def test_error_is_shown_truncated():
    view = _running(_progreso(_site("a", "error", error="x" * 200)))

    line = site_line(view.rows()[0])

    assert "error" in line and len(line) < 120 and line.endswith("...")


def test_render_lines_is_summary_then_one_row_per_site():
    view = _running(_progreso(_site("a", "done", 1, 1000), _site("b", "esperando")))

    lines = render_lines(view, now=datetime(2026, 10, 12, 10, 0, 1, tzinfo=timezone.utc))

    assert len(lines) == 3
    assert "a" in lines[1] and "b" in lines[2]


def test_render_lines_without_progreso_says_what_the_status_is():
    view = ScrapeView()
    view.apply("status", {"status": "IDLE", "mensaje": "ocioso"})

    assert render_lines(view) == ["IDLE · ocioso"]
