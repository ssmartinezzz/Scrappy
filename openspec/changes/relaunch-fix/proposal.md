# Proposal: relaunch-fix

**Date:** 2026-06-15  
**Status:** proposed

---

## Problem

Three separate issues that together degrade the "re-scrape to retrain" workflow:

1. **Training silently skipped on re-launch** — When the user re-launches scraping within 24 hours of the last run (the typical use case for iterative retraining), `PythonRunner.entrenarEnBackground()` detects a "fresh" model and exits immediately. Fase B never runs because training never starts. The user has no visibility into this.

2. **Log quality regression** — The console goes silent for up to 2 minutes while the Python ML pipeline runs. `statusMsg` is never updated during aggregation or training. The frontend progress bar freezes at ~92% with no feedback. Log statements are missing at key phase transitions in `ResultAggregator` and `ScraperService`.

3. **No way to reset DB data** — The only DB operations available are full export (binary download) and full import (replace). There is no way to clear scraped products or ML scores from the dashboard. Users who want a clean slate before a new scraping run must manually delete `scraper.db`.

---

## Proposed Solution

### Fix 1 — `forceRetrain` flag on `/api/scrape`

Add an optional `forceRetrain=true` query parameter to `POST /api/scrape`. When set, the 24-hour freshness gate in `PythonRunner.entrenarEnBackground()` is bypassed and training always runs. The flag is wired through `ApiController → ScraperService → ResultAggregator → PythonRunner`.

The frontend config tab gets a "Forzar reentrenamiento" checkbox that sets this flag when the user explicitly wants to retrain.

Also fix the `db_path_hint` bug in `ml_pipeline.py` line 481 (uses `sys.argv[1]` which is the JSON file path, not the DB path).

### Fix 2 — Log gaps + `statusMsg` during ML phase

Add targeted `LOG.info` statements at the key silent gaps:
- `ResultAggregator.agregar()` before/after `pythonRunner.ejecutar()`
- `ScraperService.ejecutarScraping()` — update `statusMsg` to "Procesando datos y ML..." before aggregation, and "Entrenando modelo..." when background training starts
- `PythonRunner.entrenarEnBackground()` — log explicitly when the 24h gate skips training (message includes age in hours) so the user understands why

Since the frontend already polls `statusMsg`, updating it in the backend is enough to propagate to the UI with no frontend API changes.

### Fix 3 — DB clear endpoints + dashboard buttons

Two new `DELETE` endpoints:
- `DELETE /api/db/productos` — truncates `productos`, `precio_historico`, `categoria_stats`; nulls in-memory `lastResult` in `ScraperService` and ML output in `ResultAggregator`
- `DELETE /api/db/ml` — truncates `ml_output` only; nulls in-memory ML state in `ResultAggregator`

Both endpoints are guarded: return `409 Conflict` if a scraping run is in progress.

In the dashboard config tab, two new buttons appear below the existing export/import section:
- "Borrar todo (catálogo + historial)" — with `window.confirm()` 
- "Borrar solo datos ML" — with `window.confirm()`

---

## Scope

**In scope:**
- `PythonRunner.java` — gate bypass logic, `forceRetrain` parameter
- `ResultAggregator.java` — pass `forceRetrain`, add log statements
- `ScraperService.java` — accept `forceRetrain`, update `statusMsg` during ML phase
- `ApiController.java` — expose `forceRetrain` param + two DELETE endpoints
- `DatabaseService.java` — add `limpiarProductos()` and `limpiarMlOutput()` methods
- `ml_pipeline.py` — fix `db_path_hint` bug
- `index.html` — checkbox for `forceRetrain`, two clear buttons in config tab

**Out of scope:**
- Adding JUnit test infrastructure (no test infra exists)
- Frontend build pipeline changes (SPA is vanilla JS, no build step)
- Any changes to the Python training model architecture or hyperparameters
- Vans/Grimoldi platform investigation

---

## Success Criteria

1. User can re-launch scraping with `forceRetrain=true` and see Fase B complete in logs
2. Console shows meaningful progress messages during the ML aggregation phase (no more 2-min silence)
3. `statusMsg` visible in the frontend reflects ML phase progress
4. User can clear product catalog from the config tab with a confirmation dialog
5. User can clear ML scores independently from the config tab
6. Neither clear button works while a scraping run is in progress
