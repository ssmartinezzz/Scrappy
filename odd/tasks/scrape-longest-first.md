# Scrape longest-first (LPT scheduling)

**Objective:** cut full-run wall time by submitting the slowest sites first.

**Problem:** sites are submitted to the fixed pool (`threads.paralelos`, default 8) in
config order. The two slowest (Vaypol ~15 min, Venex ~13.5 min) sit at the end of the list
and wait for a free thread, so a run lasts queue-wait + slowest instead of ~slowest.

**Why the DB cannot be used as-is:** `ScraperService` calls `registrarSitioEnCurso` in the
submit loop, so every `scrape_run_site.started_at` is the submit instant. 2026-10-05 run:
all 29 rows start 15:06:38; Venex `finished_at - started_at` = 1322 s vs 813 s measured by
the scraper. The progress UI also shows all 29 sites EN_CURSO while only 8 run.

**Scope:** backend only, no schema change (duration stays derived, 3NF, V29).
**Constraints:** ACID write path untouched (upsert, soft-delete, resume). Resume already
treats PENDING and RUNNING alike (`ScrapeRunRepository.sitiosEn`), so a not-yet-started
site staying PENDING is safe.

**TDD:** on — `CONTRIBUTING.md` CODE-1. Runner (TEST-2):
`JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`

## Tasks

- [x] T1 Mark a site RUNNING (DB `started_at` + progress EN_CURSO) when its task actually
      starts inside the worker, not at submit.
- [x] T2 `ScrapeRunPort.duracionesHistoricas()`: per `sitio_key`, median of
      `finished_at - started_at` over its last 3 `DONE` rows with both timestamps set.
      Repository test on real Postgres.
- [x] T3 `OrdenDeSitios` (pure): sort sites by historical duration desc; sites without
      history first, in config order; ties keep config order. Unit test.
- [x] T4 Wire it in `ScraperService` before submit; history read failure → config order + WARN.
- [x] T5 Measure: replay (before/after) + one real full run after.
- [x] T6 (added after T5's first run) Vaypol `__NEXT_DATA__` parser: the site renamed `slug`→`url`
      (now `<slug>-<id>-<variant>`) and `price`→`all_prices`; every product parsed to price 0 and
      was dropped, so the scraper fell back to 12 DOM cards per page (of 60 in the payload) and
      fetched every product page for its image. `VaypolPageNextDataTest` on a trimmed real payload.

## Acceptance

- Full suite green with `clean`.
- Replay of the logged full runs shows LPT ≤ current order on every run.
- Real run after: `started_at` differs per site; wall time reported vs the baseline runs.

## Evidence

Baseline replay (8 threads, real per-site durations from `scraper/logs`, LPT order from the
PREVIOUS full run). The simulator reproduces every real run to 0.1 min:

| run | real | sim current | sim LPT | floor (slowest site) |
|---|---|---|---|---|
| 2026-09-28 14:52 | 19.3 | 19.3 | 16.3 | 16.3 |
| 2026-09-29 17:17 | 18.0 | 18.0 | 14.0 | 14.0 |
| 2026-10-01 17:32 | 19.5 | 19.5 | 15.5 | 15.5 |
| 2026-10-02 21:19 | 20.0 | 20.0 | 16.4 | 16.4 |
| 2026-10-05 15:06 | 22.0 | 22.0 | 15.2 | 15.2 |

(minutes, site phase only; the 2026-09-28 row uses its own durations, no earlier full run.)

**Note on the first run after deploy:** existing history includes queue wait, which inflates
late-queued sites — exactly the ones that need to go first. It washes out in ≤3 runs.

Implementation checks (2026-10-07): RED observed per task (compile error on the missing
symbol); full suite `clean test` → `Tests run: 3561, Failures: 0, Errors: 0, Skipped: 7`
(skips are PythonRunner, unrelated), ArchUnit green.

Review fix: `correrSitio` first published `ProgressData(total, 0, 0, ...)` from the worker,
so a site starting mid-run reset the UI counters to 0. RED `lateStartKeepsTheCounters`
(expected 5, got 0) → now reads the published counters under `progreso`'s monitor. Replaced
the vacuous `queuedSiteIsNotMarked` (called nothing, could not fail).

T5 first real run (2026-10-07 12:49, LPT only): 39m55s — Vaypol 2339 s, its image enrichment
alone 24.5 min. Hypothesis "Vaypol+City share a backend (same 3 IPs) and LPT made them overlap"
was REFUTED by a control run of Vaypol alone: 2455 s, 41m20s. Vaypol was simply slow that day
(~4.5 s per product page × 1992 / 6 threads), and the enrichment existed only because the parser
was broken. No per-host limit needed.

T6 checks: RED (0 products, same as prod log) → GREEN; URL rule validated on the real page (12/12
card hrefs reproduced; 30/31 stored prices equal — the 31 in DB are the 12-per-page subset).
Suite: `Tests run: 3564, Failures: 0, Errors: 0, Skipped: 7`.

T5 result (2026-10-07 14:40, LPT + T6, real full run): **14m07s**, 32,556 products (was 22,972).
Vaypol 9,960 products in 715 s (same day before T6: 1,992 in 2,339 s); City 1,945 in 107 s
(was 396 in 395 s); 9960/9960 with image, no enrichment. Identity: 5 old Vaypol URLs and 1 City
URL deactivated; 9,209 historical Vaypol rows re-seen (same URL rule as when the parser worked).
Replay of THIS run's durations: config order 21.1 min vs LPT 13.1 min (floor = Venex 13.1).
Baseline real full runs (master): 18.0–22.0 min site phase.

## Next step

None. Merged as #293 (a36aea4, 2026-10-07), on top of #294.
