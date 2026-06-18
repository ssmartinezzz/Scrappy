# Design: ML Training & Classification Fixes

**Change:** ml-training-fixes  
**Date:** 2026-06-16  
**Status:** design

---

## Architecture Decisions

### AD1 — `img_size` propagation via module-level variable (not return value)

**Options considered:**
- A) Return `img_size` as a 5th value from `load_trained_models` → changes every call site
- B) Attach `img_size` as an attribute on `img_model` → non-standard, breaks type hints
- C) Store in a module-level variable `_IMG_SIZE = 224` updated at load time → single call site change

**Decision: C**. `load_trained_models` sets a module-level `_IMG_SIZE` when it loads a checkpoint. `predict_category_image` reads `_IMG_SIZE` instead of hardcoding 224. Zero call-site changes elsewhere. Simple and localised.

---

### AD2 — `load_image_dataset` extracts to a standalone function

**Decision:** Extract a new function `load_image_dataset(db_path, img_dir, max_images=3000)` that:
1. Queries the DB (same SQL as current `download_images`)
2. Applies `corregir_etiqueta` + `CAT_PARENTS` merging (same logic as `clean_labels`, inlined — not shared via import since both scripts are standalone)
3. Returns `[(path, cleaned_label)]` pairs

`download_images` becomes a pure download helper: takes `[(prod_url, img_url, cat)]` rows and returns `[(path, cat)]`. `train_image_model` calls `load_image_dataset` first, then `download_images`.

Inlining the cleaning logic (not importing from `clean_labels`) keeps both scripts self-contained, which matters because they're extracted from the JAR at runtime.

---

### AD3 — Ensemble label guard uses `frozenset` for O(1) lookup

The text model's label set (`le.classes_`) is converted to a `frozenset` once at load time. The check in the ensemble is:

```python
_TEXT_LABEL_SET: frozenset = frozenset()  # populated in load_trained_models

# in ensemble:
if img_cat and img_cat not in _TEXT_LABEL_SET:
    img_cat, img_conf = None, 0.0
```

No performance cost at inference time.

---

### AD4 — PIL verify() for cache validation

PIL's `Image.verify()` reads the file header + trailer without decoding pixels — fast. Called inside `download_images` on pre-existing cached files only (new downloads don't need it). If it raises, delete and re-download once. Second failure → skip silently.

```python
if fpath.exists():
    try:
        with Image.open(fpath) as im: im.verify()
    except Exception:
        fpath.unlink(missing_ok=True)
        # fall through to download
```

---

### AD5 — Parallel downloads via ThreadPoolExecutor with deadline

```python
from concurrent.futures import ThreadPoolExecutor, as_completed, TimeoutError
import time

DOWNLOAD_WORKERS = 8
GLOBAL_DEADLINE_S = 180

def _download_one(prod_url, img_url, img_dir):
    """Returns (fpath, cat) or None on failure."""
    ...

deadline = time.monotonic() + GLOBAL_DEADLINE_S
futures = {}
with ThreadPoolExecutor(max_workers=DOWNLOAD_WORKERS) as ex:
    for prod_url, img_url, cat in rows:
        futures[ex.submit(_download_one, prod_url, img_url, img_dir)] = cat

    for fut in as_completed(futures, timeout=GLOBAL_DEADLINE_S):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            ex.shutdown(wait=False, cancel_futures=True)
            break
        result = fut.result()
        if result:
            downloaded.append(result)
```

`_download_one` is a module-level function (required for `concurrent.futures` on Windows — same reason as `ProductImageDataset`).

---

### AD6 — `TrainingStatus` as a record in PythonRunner

```java
public record TrainingStatus(
    boolean running,
    String  phase,     // "text", "image", "image_download", "idle", "timeout", "error"
    int     pct,
    String  msg,
    String  startedAt  // ISO-8601 string, null if idle
) {
    public static TrainingStatus idle() {
        return new TrainingStatus(false, "idle", 0, "", null);
    }
}
```

`PythonRunner` holds `private final AtomicReference<TrainingStatus> trainingStatus = new AtomicReference<>(TrainingStatus.idle())`.

Updated from the stdout-reading loop in `entrenarEnBackground` whenever a `{"phase":..., "pct":..., "msg":...}` JSON line is parsed.

---

### AD7 — `ApiController.mlEntrenar` delegates to `PythonRunner`

`POST /api/ml/entrenar` becomes:

```java
@PostMapping("/ml/entrenar")
public ResponseEntity<Object> mlEntrenar(
        @RequestParam(defaultValue = "false") boolean images,
        @RequestParam(defaultValue = "8") int epochs) {

    if (pythonRunner.isTrainingRunning())
        return ResponseEntity.badRequest()
            .body(Map.of("error", "Entrenamiento ya en curso"));

    pythonRunner.entrenarEnBackground(dbPath, true, images, epochs);
    return ResponseEntity.ok(Map.of("status", "started"));
}
```

The `images` and `epochs` params are forwarded. `entrenarEnBackground` signature changes to:

```java
public void entrenarEnBackground(String dbPath, boolean forceRetrain,
                                  boolean withImages, int epochs)
```

The old overloads remain as delegation bridges to avoid breaking internal callers:
```java
public void entrenarEnBackground(String dbPath, boolean forceRetrain) {
    entrenarEnBackground(dbPath, forceRetrain, false, 8);
}
public void entrenarEnBackground(String dbPath) {
    entrenarEnBackground(dbPath, false, false, 8);
}
```

---

### AD8 — `GET /api/ml/estado` extended with training status

Current response:
```json
{ "hasTextModel": true, "hasImageModel": false, "textMeta": {...} }
```

Extended response:
```json
{
  "hasTextModel": true,
  "hasImageModel": false,
  "textMeta": {...},
  "training": {
    "running": false,
    "phase": "idle",
    "pct": 0,
    "msg": "",
    "startedAt": null
  }
}
```

`ApiController.mlEstado()` calls `pythonRunner.getTrainingStatus()` to populate the `training` block. The `mlTrainingRunning` and `mlTrainingResult` fields in `ApiController` are removed.

---

### AD9 — Timeout values

| Operation | Timeout | Rationale |
|-----------|---------|-----------|
| `tieneCuda` / `tienePytorch` | 10s | `import torch` should complete in <5s on healthy setup |
| Text-only training | 15 min | Text model trains in ~30s on any machine |
| Image training | 180 min | 15 epochs × EfficientNet-B3 on CPU can take up to 2h |
| Download phase | 180s | 8 workers × 5s timeout per image: max 3000/8 × 5 = 1875s worst case per worker, but deadline caps it |

---

## File Change Map

| File | Changes |
|------|---------|
| `ml_pipeline.py` | AD1, AD3 — `_IMG_SIZE` module var, `_TEXT_LABEL_SET` frozenset, `load_trained_models` reads `img_size` from checkpoint |
| `ml_train.py` | AD2, AD4, AD5 — `load_image_dataset`, PIL verify in `download_images`, `_download_one` module-level, ThreadPoolExecutor with deadline |
| `PythonRunner.java` | AD6, AD7 (sig change), AD9 — `TrainingStatus` record, `trainingStatus` field, timeouts in `tieneCuda`/`tienePytorch`/`waitFor`, expose `getTrainingStatus()` / `isTrainingRunning()` |
| `ApiController.java` | AD7, AD8 — delegate `mlEntrenar`, remove duplicate fields, extend `mlEstado` response |

---

## Interface Contracts

### `PythonRunner` public interface additions
```java
// New
public TrainingStatus getTrainingStatus()
public boolean isTrainingRunning()
public void entrenarEnBackground(String dbPath, boolean forceRetrain,
                                  boolean withImages, int epochs)

// Unchanged (kept for backward compat)
public JsonNode ejecutar(String productosJson)
public void entrenarEnBackground(String dbPath)
public void entrenarEnBackground(String dbPath, boolean forceRetrain)
```

### `ml_pipeline.py` module-level state (new)
```python
_IMG_SIZE: int = 224           # set by load_trained_models
_TEXT_LABEL_SET: frozenset = frozenset()  # set by load_trained_models
```

### `ml_train.py` new functions
```python
def load_image_dataset(db_path: str, img_dir: Path, max_images: int = 3000) -> list[tuple[str, str]]
def _download_one(args: tuple) -> tuple[str, str] | None  # module-level for pickling
```

---

## Implementation Order

1. `ml_pipeline.py` (AD1, AD3) — self-contained, no Java changes needed
2. `ml_train.py` (AD2, AD4, AD5) — self-contained, no Java changes needed
3. `PythonRunner.java` (AD6, AD9) — add `TrainingStatus`, fix timeouts
4. `ApiController.java` (AD7, AD8) — delegate + extend estado endpoint
