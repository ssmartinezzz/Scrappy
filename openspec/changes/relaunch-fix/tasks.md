# Tasks: relaunch-fix

**Date:** 2026-06-15  
**Status:** archived

---

## Implementation Checklist

### Group A — DB Layer (no dependencies)

- [x] **A1** `DatabaseService.java` — add `limpiarProductos()`: `DELETE FROM productos`, `DELETE FROM precio_historico`, `DELETE FROM categoria_stats`, commit in transaction
- [x] **A2** `DatabaseService.java` — add `limpiarMlOutput()`: `DELETE FROM ml_output`, commit in transaction

### Group B — Service Layer (after A)

- [x] **B1** `ScraperService.java` — add `boolean forceRetrain` field; update `iniciarScraping(...)` signature to accept it; store in field
- [x] **B2** `ScraperService.java` — in `ejecutarScraping()`, update `statusMsg` to "Procesando y agregando resultados..." before calling `aggregator.agregar()`; update to "Entrenando modelo ML en background..." after aggregation; keep "Completado" at end
- [x] **B3** `ScraperService.java` — add `clearLastResult()` public method that sets `lastResult = null`
- [x] **B4** `ResultAggregator.java` — add `forceRetrain` parameter to `agregar(...)` method signature; forward to `pythonRunner.entrenarEnBackground(dbPath, forceRetrain)`
- [x] **B5** `ResultAggregator.java` — add `LOG.info` banner before `pythonRunner.ejecutar()` ("Ejecutando pipeline ML, esto puede tomar hasta 2 minutos...")
- [x] **B6** `ResultAggregator.java` — add `LOG.info` after `pythonRunner.ejecutar()` returns ("Pipeline ML completado")
- [x] **B7** `ResultAggregator.java` — add `LOG.info` when `entrenarEnBackground` is called ("Lanzando entrenamiento en background...")
- [x] **B8** `ResultAggregator.java` — add `clearMlOutput()` public method that nulls the in-memory ML output field

### Group C — ML Runner (after B)

- [x] **C1** `PythonRunner.java` — update `entrenarEnBackground(String dbPath)` signature to `entrenarEnBackground(String dbPath, boolean forceRetrain)`
- [x] **C2** `PythonRunner.java` — wrap existing 24h age check in `if (!forceRetrain)` block
- [x] **C3** `PythonRunner.java` — when forceRetrain=true, log "[ML-TRAIN] forceRetrain=true — saltando verificación de antigüedad"
- [x] **C4** `PythonRunner.java` — when training actually starts, add LOG.info banner: "[ML-TRAIN] ===== Iniciando entrenamiento del modelo ====="
- [x] **C5** `ml_pipeline.py` — fix `db_path_hint` on line ~481: derive from `Path(sys.argv[1]).parent / "scraper.db"` instead of using `sys.argv[1]` directly

### Group D — API Layer (after A + B + C)

- [x] **D1** `ApiController.java` — add `@RequestParam(defaultValue = "false") boolean forceRetrain` to `scrape()` method; pass to `service.iniciarScraping(..., forceRetrain)`
- [x] **D2** `ApiController.java` — add `DELETE /api/db/productos` endpoint: check RUNNING → 409; call `db.limpiarProductos()`, `service.clearLastResult()`, `aggregator.clearMlOutput()`; return 200 ok
- [x] **D3** `ApiController.java` — add `DELETE /api/db/ml` endpoint: check RUNNING → 409; call `db.limpiarMlOutput()`, `aggregator.clearMlOutput()`; return 200 ok

### Group E — Frontend (after D)

- [x] **E1** `SplashPanel.jsx` + `api.js` — add "Forzar reentrenamiento del modelo" checkbox in launch tab; passes `forceRetrain=true` to `startScrape()` when checked
- [x] **E2** `SplashPanel.jsx` + `api.js` — add "Borrar catálogo y historial" button in config tab DB section with `window.confirm()` + `DELETE /api/db/productos`
- [x] **E3** `SplashPanel.jsx` + `api.js` — add "Borrar solo datos ML" button in config tab DB section with `window.confirm()` + `DELETE /api/db/ml`
- [x] **E4** `SplashPanel.jsx` — display success/error feedback in `clearMsg` state; handles 409 with "Esperá a que termine el scraping"

---

## Delivery

Single PR. All 7 files, ~120–150 lines changed. No new dependencies. No build changes. No DB migrations (only `DELETE FROM` on existing tables).

**Estimated changed lines:** ~150 (within 400-line budget — single PR)

---

## Verification Checklist

- [ ] Build passes: `mvn -f scraper/pom.xml clean package -DskipTests`
- [ ] `POST /api/scrape?forceRetrain=false` → same behavior as today (24h gate active)
- [ ] `POST /api/scrape?forceRetrain=true` → `[ML-TRAIN] ===== Iniciando entrenamiento =====` appears in log regardless of model age
- [ ] During scraping run, console shows "Procesando y agregando resultados..." and "Entrenando modelo ML en background..." as status messages
- [ ] `DELETE /api/db/productos` while IDLE → 200; subsequent `GET /api/data` returns `[]`
- [ ] `DELETE /api/db/ml` while IDLE → 200; subsequent `GET /api/tendencias` returns empty/null
- [ ] `DELETE /api/db/productos` while RUNNING → 409 with message
- [ ] Frontend "Borrar catálogo" button shows confirm dialog; canceling makes no API call
- [ ] Frontend "Borrar catálogo" button shows success message after 200
- [ ] `ml_pipeline.py` loads models from correct directory on second run
