# Design: relaunch-fix

**Date:** 2026-06-15  
**Status:** draft

---

## Architecture Overview

All three fixes are additive: new parameters, new methods, new endpoints, new log statements. No existing behavior is removed except the silent training skip (which becomes explicit + bypassable).

The call chain for Fix 1:

```
ApiController.scrape(forceRetrain)
  → ScraperService.iniciarScraping(..., forceRetrain)
    → ResultAggregator.agregar(..., forceRetrain)
      → PythonRunner.entrenarEnBackground(dbPath, forceRetrain)
        → [gate bypassed if forceRetrain=true] → ml_train.py subprocess
```

---

## Fix 1 — forceRetrain flag

### ApiController.java

```java
@PostMapping("/api/scrape")
public ResponseEntity<String> scrape(
    @RequestParam(defaultValue = "0") double precioMin,
    @RequestParam(defaultValue = "999999") double precioMax,
    @RequestParam(required = false) List<String> sitios,
    @RequestParam(defaultValue = "false") boolean forceRetrain   // NEW
) {
    service.iniciarScraping(precioMin, precioMax, sitios, forceRetrain);
    ...
}
```

### ScraperService.java

Add `forceRetrain` parameter to `iniciarScraping(...)` and forward it when calling `aggregator.agregar(...)`.

```java
public void iniciarScraping(double precioMin, double precioMax, 
                             List<String> sitios, boolean forceRetrain) {
    ...
    this.forceRetrain = forceRetrain;  // store in field for use in ejecutarScraping
    ...
}
```

Update `ejecutarScraping()` to pass it:
```java
aggregator.agregar(resultados, dbPath, forceRetrain);
```

### ResultAggregator.java

```java
public void agregar(List<ScrapeResult> resultados, String dbPath, boolean forceRetrain) {
    ...
    pythonRunner.entrenarEnBackground(dbPath, forceRetrain);  // pass through
}
```

### PythonRunner.java

```java
public void entrenarEnBackground(String dbPath, boolean forceRetrain) {
    ...
    if (modelExists && !forceRetrain) {
        long age = System.currentTimeMillis() - Files.getLastModifiedTime(textModel).toMillis();
        if (age < 24L * 3600 * 1000) {
            LOG.info("[ML-TRAIN] Modelo reciente ({} horas). Usá forceRetrain=true para re-entrenar.", 
                     age / 3600000);
            return;
        }
    }
    if (forceRetrain) {
        LOG.info("[ML-TRAIN] forceRetrain=true — saltando verificación de antigüedad del modelo.");
    }
    // proceed with training...
}
```

### ml_pipeline.py

Replace line ~481:
```python
# Before (wrong):
db_path_hint = sys.argv[1] if len(sys.argv) > 1 else "scraper.db"

# After (correct):
# sys.argv[1] is ml_productos.json; resolve model dir from its parent
json_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("ml_productos.json")
db_path_hint = str(json_path.parent / "scraper.db")
```

### index.html — forceRetrain checkbox

In the config panel, add to the "Iniciar scraping" section:
```html
<label class="toggle-label">
  <input type="checkbox" id="forceRetrain"> Forzar reentrenamiento del modelo
</label>
```

When the user submits the scraping form, append `&forceRetrain=true` if the checkbox is checked.

---

## Fix 2 — Log quality + statusMsg

### ScraperService.java

In `ejecutarScraping()`, before calling `aggregator.agregar()`:
```java
statusMsg = "Procesando y agregando resultados...";
LOG.info("[RUN] Iniciando agregación y pipeline ML...");
```

After training is triggered (or detect it from `entrenarEnBackground` — see below):
```java
statusMsg = "Entrenando modelo ML en background...";
```

After aggregation completes:
```java
statusMsg = "Completado";
```

### ResultAggregator.java

```java
public void agregar(...) {
    LOG.info("[AGG] Iniciando agregación: {} sitios, {} resultados totales", ...);
    ...
    LOG.info("[AGG] Ejecutando pipeline ML (esto puede tomar hasta 2 minutos)...");
    pythonRunner.ejecutar(jsonPath, dbPath);
    LOG.info("[AGG] Pipeline ML completado. Enriqueciendo productos...");
    ...
    LOG.info("[AGG] Lanzando entrenamiento de modelo en background...");
    pythonRunner.entrenarEnBackground(dbPath, forceRetrain);
}
```

### PythonRunner.java

Existing per-line stderr forwarding is good. Add a start banner:
```java
LOG.info("[ML-TRAIN] ===== Iniciando entrenamiento del modelo =====");
LOG.info("[ML-TRAIN] Esto puede tomar entre 3 y 40 minutos dependiendo del volumen de datos.");
```

---

## Fix 3 — DB clear endpoints

### DatabaseService.java

```java
public void limpiarProductos() throws SQLException {
    try {
        conn.createStatement().execute("DELETE FROM productos");
        conn.createStatement().execute("DELETE FROM precio_historico");
        conn.createStatement().execute("DELETE FROM categoria_stats");
        conn.commit();
        LOG.info("[DB] Catálogo, historial y stats de categorías eliminados.");
    } catch (SQLException e) {
        conn.rollback();
        throw e;
    }
}

public void limpiarMlOutput() throws SQLException {
    try {
        conn.createStatement().execute("DELETE FROM ml_output");
        conn.commit();
        LOG.info("[DB] Datos ML eliminados.");
    } catch (SQLException e) {
        conn.rollback();
        throw e;
    }
}
```

### ApiController.java

```java
@DeleteMapping("/api/db/productos")
public ResponseEntity<String> limpiarProductos() {
    if (service.getStatus() == EstadoScraping.RUNNING) {
        return ResponseEntity.status(409).body("Hay un scraping en curso. Esperá a que termine.");
    }
    try {
        db.limpiarProductos();
        service.clearLastResult();          // null in-memory cache
        aggregator.clearMlOutput();         // null in-memory ML state
        return ResponseEntity.ok("Catálogo eliminado.");
    } catch (Exception e) {
        return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
    }
}

@DeleteMapping("/api/db/ml")
public ResponseEntity<String> limpiarMl() {
    if (service.getStatus() == EstadoScraping.RUNNING) {
        return ResponseEntity.status(409).body("Hay un scraping en curso. Esperá a que termine.");
    }
    try {
        db.limpiarMlOutput();
        aggregator.clearMlOutput();         // null in-memory ML state
        return ResponseEntity.ok("Datos ML eliminados.");
    } catch (Exception e) {
        return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
    }
}
```

### ScraperService.java — clearLastResult()

Add a public method:
```java
public void clearLastResult() {
    this.lastResult = null;
}
```

### ResultAggregator.java — clearMlOutput()

Add a public method:
```java
public void clearMlOutput() {
    this.lastMlOutput = null;  // adjust field name to match actual field
}
```

### index.html — clear buttons

Below the existing "💾 Base de datos" export/import section in the config tab:

```html
<div class="db-actions danger-zone">
  <h4>Borrar datos</h4>
  <button id="btnBorrarCatalogo" class="btn-danger">
    🗑 Borrar catálogo y historial
  </button>
  <button id="btnBorrarMl" class="btn-danger-soft">
    🗑 Borrar solo datos ML
  </button>
  <p id="clearMsg" class="msg"></p>
</div>
```

JS handlers:
```javascript
document.getElementById('btnBorrarCatalogo').onclick = async () => {
  if (!confirm('¿Seguro? Esto borra todos los productos, el historial de precios y las estadísticas de categorías. No se puede deshacer.')) return;
  const r = await fetch('/api/db/productos', { method: 'DELETE' });
  document.getElementById('clearMsg').textContent = r.ok ? '✓ Catálogo borrado.' : `Error ${r.status}: ${await r.text()}`;
};

document.getElementById('btnBorrarMl').onclick = async () => {
  if (!confirm('¿Seguro? Esto borra los scores, badges y tendencias ML. Los productos quedan intactos.')) return;
  const r = await fetch('/api/db/ml', { method: 'DELETE' });
  document.getElementById('clearMsg').textContent = r.ok ? '✓ Datos ML borrados.' : `Error ${r.status}: ${await r.text()}`;
};
```

---

## Dependency Order for Implementation

1. `DatabaseService.java` — new methods (no dependencies)
2. `ScraperService.java` — add `forceRetrain` field, `clearLastResult()`, `statusMsg` updates
3. `ResultAggregator.java` — add `forceRetrain` param, log statements, `clearMlOutput()`
4. `PythonRunner.java` — gate bypass logic
5. `ApiController.java` — new param + new DELETE endpoints (depends on 1-4)
6. `ml_pipeline.py` — path fix (independent)
7. `index.html` — checkbox + buttons (depends on 5)

---

## No Breaking Changes

- `/api/scrape` with no `forceRetrain` param behaves identically to current
- All existing endpoints unchanged
- `DatabaseService` existing methods unchanged
- `ScraperService` and `ResultAggregator` existing method signatures changed (add param) — internal only, no external callers outside `ApiController`
