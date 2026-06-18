# Exploration: relaunch-fix

**Date:** 2026-06-15  
**Change:** relaunch-fix  
**Status:** done

---

## Summary

Three distinct problems were identified:

1. **Fase B bug** — `PythonRunner.entrenarEnBackground()` has a 24-hour freshness gate that silently skips ML training on re-launch within the same day. Fase B never runs because training is never started.
2. **Log quality** — Missing log statements during aggregation/ML phase + `statusMsg` never updated during the 2-minute Python run.
3. **DB clear button** — No `DELETE/truncate` endpoints or `DatabaseService` methods exist; only export/import is implemented.

---

## Problem 1 — Re-launch bug: Fase B never runs

### Root Cause

`PythonRunner.entrenarEnBackground()` (lines 128–136) checks if the trained model file is less than 24 hours old. If so, it returns immediately — `ml_train.py` is never called, and Fase B (deep fine-tuning) never executes.

```java
if (modelExists) {
    long age = System.currentTimeMillis() - Files.getLastModifiedTime(textModel).toMillis();
    if (age < 24L * 3600 * 1000) {
        LOG.info("[ML-TRAIN] Modelo reciente ({} horas), saltando re-entrenamiento", age / 3600000);
        return;  // ← BAILS OUT — user never gets Fase B on same-day re-launch
    }
}
```

### Secondary Issue

`ml_pipeline.py` line 481 uses `sys.argv[1]` (which is `ml_productos.json`) as the `db_path_hint` for model loading via `Path(db_path_hint).parent / "_models"`. This accidentally works when both files share the same working directory but is semantically wrong and fragile.

### Recommended Fix

Add a `forceRetrain` boolean parameter to `POST /api/scrape`. Wire it through:
- `ScraperService.iniciarScraping(precioMin, precioMax, sitios, forceRetrain)`
- `ResultAggregator.agregar(..., forceRetrain)`
- `PythonRunner.entrenarEnBackground(dbPath, forceRetrain)` — bypass gate when true

### Files Affected

- `scraper/src/main/java/ar/scraper/ml/PythonRunner.java` — primary fix (lines 128–136)
- `scraper/src/main/java/ar/scraper/aggregator/ResultAggregator.java` — pass flag through
- `scraper/src/main/java/ar/scraper/web/ScraperService.java` — accept & pass flag from API
- `scraper/src/main/java/ar/scraper/web/ApiController.java` — expose `forceRetrain` param
- `scraper/src/main/resources/ml/ml_pipeline.py` — fix `db_path_hint` (cosmetic)

---

## Problem 2 — Log quality and progress visibility

### Root Cause

Coverage gaps in log statements — the configuration (`logback-spring.xml`) is correct. The `ar.scraper.run` logger falls through to the `ar.scraper` logger via prefix match — correct behavior.

### Coverage Gaps

| Phase | Status |
|-------|--------|
| ScraperService — run start/end | ✅ Good |
| ScraperService — per-site result | ✅ Good |
| ResultAggregator — ML invocation start | ❌ Missing — console goes silent for up to 120s |
| ResultAggregator — background training triggered | ❌ Missing |
| PythonRunner — during Python execution | Only stderr forwarded — no phase banners |
| `statusMsg` during ML phase | ❌ Never updated — frontend shows nothing |

### Recommended Fix

1. Add `LOG.info` in `ResultAggregator.agregar()` before and after `pythonRunner.ejecutar()`
2. Update `statusMsg` in `ScraperService.ejecutarScraping()` to "Procesando ML..." before calling `aggregator.agregar()`
3. Log when background training is triggered vs. skipped (already partially there — make it more prominent)

### Files Affected

- `scraper/src/main/java/ar/scraper/aggregator/ResultAggregator.java`
- `scraper/src/main/java/ar/scraper/web/ScraperService.java`
- `scraper/src/main/resources/static/index.html` — already polls `statusMsg`; no change needed if we update `statusMsg` properly

---

## Problem 3 — DB clear/dump buttons

### Current State

`ApiController.java` already has:
- `GET /api/db/export` — downloads `scraper.db` binary
- `POST /api/db/import` — replaces DB with upload

Both are wired in the config tab in `index.html`.

### What's Missing

- No `DELETE /api/db/productos` endpoint
- No `DELETE /api/db/ml` endpoint
- `DatabaseService.java` has zero truncate/clear methods

### Critical Interdependency

`ScraperService` holds `lastResult` in memory. If we only clear SQLite but not `lastResult`, `/api/data` will keep serving stale data from memory until the next scrape. Clear endpoints must call `service.setLastResult(null)` (or equivalent) and also clear the aggregator's in-memory ML output.

### Recommended Fix

1. Add `limpiarProductos()` and `limpiarMlOutput()` to `DatabaseService.java`
2. Add `DELETE /api/db/productos` and `DELETE /api/db/ml` to `ApiController.java` — both guarded against calls during RUNNING state
3. Add two buttons to the config tab in `index.html` — with `window.confirm()` dialogs
4. Add null-check / clear for in-memory state when clearing products

### Files Affected

- `scraper/src/main/java/ar/scraper/db/DatabaseService.java`
- `scraper/src/main/java/ar/scraper/web/ApiController.java`
- `scraper/src/main/resources/static/index.html`

---

## Interdependencies

- Problems 1 and 3 are fully independent of each other.
- Problem 2 (`statusMsg` update) interacts with Problem 1: if training is skipped (24h gate), `statusMsg` should reflect that — "ML: entrenamiento saltado (modelo reciente)" — giving the user visibility into why Fase B didn't run.
- Problem 3 (`DELETE /api/db/ml`) should also reset `aggregator.lastMlOutput` in memory, or `/api/tendencias` will serve stale data.

---

## Risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| `lastResult` in-memory / SQLite split-brain on clear | HIGH | Clear endpoint must null in-memory state in ScraperService + aggregator |
| Back-to-back training if user scrapes rapidly with `forceRetrain=true` | MEDIUM | Guard: if training thread is already running, skip the second launch |
| `DatabaseService` DDL copy-paste bug (repeated `precios_externos` creation) | LOW | Harmless (`IF NOT EXISTS`), clean up opportunistically |
