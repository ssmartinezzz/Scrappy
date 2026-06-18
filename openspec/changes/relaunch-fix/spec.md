# Spec: relaunch-fix

**Date:** 2026-06-15  
**Status:** draft

---

## Feature 1 — forceRetrain flag

### REQ-1.1 — API parameter
`POST /api/scrape` MUST accept an optional query parameter `forceRetrain` (boolean, default `false`).

### REQ-1.2 — Gate bypass
When `forceRetrain=true`, `PythonRunner.entrenarEnBackground()` MUST skip the 24-hour age check and always invoke `ml_train.py`.

### REQ-1.3 — Default behavior preserved
When `forceRetrain=false` (or absent), the existing 24-hour freshness gate behavior is preserved unchanged.

### REQ-1.4 — Frontend checkbox
The config tab in `index.html` MUST include a "Forzar reentrenamiento" checkbox. When checked, the next `POST /api/scrape` call includes `forceRetrain=true`.

### REQ-1.5 — ml_pipeline.py path fix
`ml_pipeline.py` line 481 MUST derive the model directory from the working directory (or the explicitly passed `--db` argument) rather than from `sys.argv[1]`.

---

## Feature 2 — Log quality

### REQ-2.1 — Aggregation phase log
`ResultAggregator.agregar()` MUST emit a `LOG.info` banner immediately before calling `pythonRunner.ejecutar()` and another immediately after it returns.

### REQ-2.2 — statusMsg during ML
`ScraperService.ejecutarScraping()` MUST update `statusMsg` to a non-empty progress string before calling `aggregator.agregar()`. The string MUST be different from the per-site completion message so the user can distinguish the phases.

### REQ-2.3 — Training skip visibility
When `entrenarEnBackground()` skips training due to the 24h gate, it MUST log the model age in hours at `LOG.info` level in a way that makes it clear why training was skipped. (This log already partially exists but must be clear enough that the user understands without reading source code.)

### REQ-2.4 — Training start log
When `entrenarEnBackground()` actually starts `ml_train.py`, it MUST log a clearly visible banner at `LOG.info` level indicating training has started (not just at DEBUG).

### REQ-2.5 — No log regressions
Existing log statements MUST NOT be removed or downgraded in level.

---

## Feature 3 — DB clear endpoints

### REQ-3.1 — Clear products endpoint
`DELETE /api/db/productos` MUST truncate the `productos`, `precio_historico`, and `categoria_stats` tables in `scraper.db`.

### REQ-3.2 — Clear ML endpoint
`DELETE /api/db/ml` MUST truncate the `ml_output` table in `scraper.db`.

### REQ-3.3 — In-memory state cleared
After `DELETE /api/db/productos`, the in-memory `lastResult` in `ScraperService` MUST be set to null so `/api/data` returns an empty result set rather than stale cached data.

### REQ-3.4 — In-memory ML state cleared
After `DELETE /api/db/ml`, the in-memory ML output in `ResultAggregator` MUST be set to null so `/api/tendencias` returns empty rather than stale data.

### REQ-3.5 — Running guard
Both DELETE endpoints MUST return `HTTP 409 Conflict` with a plain-text body if `ScraperService.getStatus() == RUNNING`.

### REQ-3.6 — DatabaseService methods
`DatabaseService` MUST expose `limpiarProductos()` and `limpiarMlOutput()` methods that execute the appropriate `DELETE FROM` statements inside a transaction and commit.

### REQ-3.7 — Frontend buttons
The config tab MUST include two buttons below the existing export/import section:
- "Borrar catálogo y historial" (calls `DELETE /api/db/productos`)
- "Borrar datos ML" (calls `DELETE /api/db/ml`)

### REQ-3.8 — Confirmation dialogs
Each button MUST show a `window.confirm()` dialog with a descriptive message before making the API call. The call is only made if the user confirms.

### REQ-3.9 — Feedback message
After a successful delete, the config tab MUST display a success message in the existing `dbMsg` or equivalent feedback area. After a 409 response, it MUST display an error message explaining the run is in progress.

---

## Acceptance Criteria (end-to-end)

| Scenario | Expected |
|----------|----------|
| Re-launch scraping without `forceRetrain` within 24h | Training skipped; log shows "modelo reciente (N horas), saltando" |
| Re-launch scraping with `forceRetrain=true` within 24h | Training runs; Fase B log appears |
| Scraping in progress + clear products | 409 returned; no data deleted |
| Clear products when idle | `productos`, `precio_historico`, `categoria_stats` empty; `/api/data` returns `[]` |
| Clear ML when idle | `ml_output` empty; `/api/tendencias` returns empty |
| Aggregation phase running | Console shows "Procesando datos y ML..." and relevant log banners |
