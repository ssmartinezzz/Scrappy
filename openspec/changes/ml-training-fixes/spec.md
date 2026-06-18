# Spec: ML Training & Classification Fixes

**Change:** ml-training-fixes  
**Date:** 2026-06-16  
**Status:** spec

---

## Part A — Classification Correctness

### R1 — IMG_SIZE must match at inference time

**R1.1** The model checkpoint saved by `train_image_model` MUST include the `img_size` used during training (already stored as `"img_size": IMG_SIZE` in the `torch.save` dict).

**R1.2** `predict_category_image` in `ml_pipeline.py` MUST read `img_size` from the checkpoint at load time and store it alongside the model.

**R1.3** `load_trained_models` MUST return `img_size` as a fifth value (or attach it to `img_model` as an attribute). The call signature changes from `(text_model, le, img_model, img_le)` to `(text_model, le, img_model, img_le, img_size)`.

**R1.4** `predict_category_image` MUST use the stored `img_size` for `T.Resize`. It MUST NOT hardcode 224.

**R1.5** If no `img_size` key exists in the checkpoint (old model), fall back to 224. Log a warning.

---

### R2 — Image model must be trained on cleaned labels

**R2.1** `train_image_model` MUST NOT use raw `categoria` values directly from the DB.

**R2.2** A new helper `load_image_dataset(db_path)` MUST fetch `(url, imagen_url, nombre, categoria)` from the DB and apply the same label cleaning pipeline as `clean_labels`:
  - Apply `corregir_etiqueta(nombre, cat)` for each row
  - Apply `CAT_PARENTS` merging when category count < `MIN_SAMPLES`

**R2.3** The resulting `(path, cleaned_label)` pairs MUST be passed to the image training loop instead of the raw DB pairs.

**R2.4** The image label encoder classes MUST be a subset of the text model's classes after cleaning. They need not be identical (image model may have fewer classes if some have insufficient images), but must not contain labels that `clean_labels` would have eliminated or merged.

---

### R3 — Ensemble label space consistency

**R3.1** When loading models in `load_trained_models`, if both `text_model` and `img_model` are loaded, log the label sets of each so mismatches are visible in the log.

**R3.2** In the ensemble logic (`predict_category_image` + `predict_category` section of `main()`), before applying an image prediction, verify that `img_cat` exists in the text model's label set (`le.classes_`). If it doesn't, treat `img_cat` as if it were `None` (discard the image prediction).

**R3.3** This check is a safety net — fixing R2 is the root fix. R3.2 prevents regressions if models are retrained out of sync.

---

### R4 — Cached image validation

**R4.1** In `download_images`, after confirming a file exists at `fpath`, attempt `Image.open(fpath).verify()`. If it raises any exception, delete the file and re-download.

**R4.2** If the re-download also fails, skip the sample (do not add to `downloaded`).

**R4.3** The validation MUST use PIL's `verify()` method (cheap, does not decode full image data).

**R4.4** Log the count of invalid cached files that were replaced at the end of `download_images`.

---

## Part B — Training Hang Prevention

### R5 — `tieneCuda()` / `tienePytorch()` must have a timeout

**R5.1** Both `tieneCuda(python)` and `tienePytorch(python)` in `PythonRunner.java` MUST store the `Process` reference and call `proc.waitFor(10, TimeUnit.SECONDS)`.

**R5.2** If the 10-second timeout elapses, call `proc.destroyForcibly()` and return `false`.

**R5.3** The `readAllBytes()` approach MUST be replaced with reading stdout only after `waitFor` confirms the process ended, OR replaced with a streaming read that terminates on timeout.

---

### R6 — Parallel image downloads with global deadline

**R6.1** `download_images` MUST replace the sequential loop with `concurrent.futures.ThreadPoolExecutor(max_workers=8)`.

**R6.2** Each download task MUST have an individual `timeout=5` seconds (unchanged).

**R6.3** The entire download phase MUST have a global wall-clock deadline of **180 seconds**. When the deadline is reached, cancel pending futures and return whatever was collected.

**R6.4** Progress reporting MUST be preserved — emit a `progress("image_download", pct, ...)` line at least every 50 images processed (success or failure).

**R6.5** At the end, log: total attempted, total downloaded, total failed, total time.

---

### R7 — `proc.waitFor()` must have a timeout in Java callers

**R7.1** In `PythonRunner.entrenarEnBackground`, replace `proc.waitFor()` (line 201) with:
  - Text-only training: `proc.waitFor(15, TimeUnit.MINUTES)`
  - Image training (when `--images` flag is set): `proc.waitFor(180, TimeUnit.MINUTES)`

**R7.2** If the timeout elapses, call `proc.destroyForcibly()` and log `[ML-TRAIN] TIMEOUT — proceso de entrenamiento terminado forzosamente`.

**R7.3** In `ApiController.mlEntrenar`, same timeouts apply. After `destroyForcibly()`, set `mlTrainingRunning` to `false` and `mlTrainingResult` to `{"status":"timeout"}`.

---

### R8 — Unified training status visible to frontend

**R8.1** `PythonRunner` MUST expose a thread-safe status field: `trainingStatus: AtomicReference<TrainingStatus>` where `TrainingStatus` is a record with `{ running: boolean, phase: String, pct: int, msg: String, startedAt: Instant }`.

**R8.2** `entrenarEnBackground` MUST update `trainingStatus` at key moments: start, each parsed `progress()` JSON line from stdout, completion, timeout, error.

**R8.3** `GET /api/ml/estado` MUST include the `trainingStatus` fields in its response so the frontend can poll it.

**R8.4** `ApiController.mlTrainingRunning` and `mlTrainingResult` MUST be unified with the status in `PythonRunner` — not two separate tracking systems.

---

### R9 — Single training code path

**R9.1** `ApiController.mlEntrenar` MUST delegate to `PythonRunner.entrenarEnBackground(dbPath, true)` instead of managing its own `ProcessBuilder`.

**R9.2** The private methods `localizarPython()` and `localizarScript()` in `ApiController` MUST be removed (they duplicate `detectarPython()` and `extraerTrainScript()` in `PythonRunner`).

**R9.3** `GET /api/ml/resultado` MUST read from the unified `PythonRunner.trainingStatus` instead of `mlTrainingResult`.

**R9.4** `POST /api/ml/entrenar` with `images=true` MUST pass `--images` to the subprocess. It MUST still work — the unification does not remove image training support.

---

## Acceptance Scenarios

### Scenario A — Correct inference size
Given a model trained with EfficientNet-B3 (IMG_SIZE=300),  
When `predict_category_image` runs,  
Then `T.Resize((300, 300))` is used, not `(224, 224)`.

### Scenario B — Old model fallback
Given a model checkpoint with no `img_size` key,  
When `load_trained_models` loads it,  
Then `img_size=224` is used and a warning is logged.

### Scenario C — Cleaned image labels
Given a product in the DB with `nombre="Remera Básica Nike"` and `categoria="Zapatilla"` (mislabeled),  
When `train_image_model` builds its dataset,  
Then `corregir_etiqueta` corrects the label to `"Remera"` before it enters training.

### Scenario D — Ensemble label guard
Given the image model predicts `"Indumentaria Superior"` and the text model's label set contains only fine-grained labels like `"Remera"`, `"Buzo"`,  
When the ensemble runs,  
Then the image prediction is discarded and the text prediction is used instead.

### Scenario E — Corrupt image skipped
Given a cached file `_models/images/abc123.jpg` is 0 bytes (partial download),  
When `download_images` processes it,  
Then it deletes the file, re-downloads it, and only includes it if the re-download succeeds.

### Scenario F — `tieneCuda` timeout
Given `import torch` hangs on CUDA driver init,  
When `tieneCuda(python)` is called,  
Then after 10 seconds the subprocess is killed and `false` is returned. Training proceeds as text-only.

### Scenario G — Parallel downloads finish in time
Given 3000 image URLs from the DB, 30% of which time out at 5s each,  
When `download_images` runs with 8 workers,  
Then it completes in under 180 seconds total (vs ~2500s sequential). The downloaded subset is used for training.

### Scenario H — Training timeout
Given image training is running and the Python process hangs after epoch 3,  
When 180 minutes elapse,  
Then `destroyForcibly()` is called, `trainingStatus` is set to `{running: false, phase: "timeout"}`, and `/api/ml/estado` reflects this.

### Scenario I — Unified status visible in frontend
Given background auto-training is triggered after a scrape,  
When the user polls `GET /api/ml/estado`,  
Then `running: true`, current `phase`, and `pct` are returned — not just the model file existence.
