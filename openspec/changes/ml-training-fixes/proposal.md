# Proposal: ML Training & Classification Fixes

**Change:** ml-training-fixes  
**Date:** 2026-06-16  
**Status:** proposed

---

## Problem

The ML training pipeline (`ml_train.py`) and the inference pipeline (`ml_pipeline.py`) have four bugs that together cause the image classifier to behave erratically and produce wrong classifications. These are not ML performance issues — they are engineering bugs in how training and inference are wired together.

---

## Root Causes Found

### Bug 1 — IMG_SIZE mismatch (Critical)
**Training** (`ml_train.py`, line 440):
```python
IMG_SIZE = 300 if arch == "efficientnet_b3" else 240
```
**Inference** (`ml_pipeline.py`, line 116):
```python
T.Resize((224, 224)),
```
The inference always resizes to 224×224 regardless of which model was trained. A model trained at 300px never sees 300px images at inference time. This alone is enough to explain "strange" classifications.

### Bug 2 — Image model trained on raw, uncleaned labels
`train_image_model` downloads images with the raw `categoria` column from the DB (line 285-290 in `ml_train.py`). It does NOT apply:
- `LABEL_CORRECTIONS` (e.g., ojotas mislabeled as zapatilla)
- `CAT_PARENTS` merging (subcategories with few samples merged to parent)
- Any normalization

The text model goes through `clean_labels()` — the image model does not. They train on different data distributions.

### Bug 3 — Label space mismatch in the ensemble
The text model can predict `"Indumentaria Superior"` (a merged parent label from `CAT_PARENTS`). The image model predicts raw categories like `"Remera"`, `"Buzo"`, `"Sweater"`. When the ensemble in `ml_pipeline.py` (line 775) does:
```python
if img_cat and img_conf > txt_conf:
    pred_cat, confianza = img_cat, img_conf
```
It's comparing predictions from incompatible label spaces. The TIPOS safety check (line 785) can't fully compensate because "Remera" and "Indumentaria Superior" are in the same semantic space but different string values.

### Bug 4 — Cached images not validated
`download_images` (ml_train.py line 302-305) skips download if file exists (`if not fpath.exists()`), but never validates that the cached file is a valid image. A partial download or a 0-byte file is included in the training dataset silently. `ProductImageDataset.__getitem__` catches PIL errors and substitutes a gray image (line 332-334), meaning corrupt files train as gray squares labeled with the wrong category.

---

## Proposed Solution

### Fix 1: Save and use IMG_SIZE at inference time
Store `img_size` in the model checkpoint (already done: `"img_size": IMG_SIZE`). Inference code must read it from the checkpoint instead of hardcoding 224.

### Fix 2: Apply clean_labels to image training data
Extract a helper function `prepare_image_dataset(db_path)` that fetches image paths along with cleaned categories (applying `LABEL_CORRECTIONS` + `CAT_PARENTS` merging). Feed these cleaned labels to `train_image_model` instead of the raw DB categories.

### Fix 3: Unify label spaces at training time
Both models must be trained with the same label space — i.e., the output of `clean_labels`. The image model must also merge subcategories with few samples using the same `CAT_PARENTS` dict and `MIN_SAMPLES` threshold.

### Fix 4: Validate cached images before use
After a cached file is confirmed to exist, attempt a fast PIL open to verify it's readable. If it fails, delete and re-download. Add a validation pass before building the dataset.

---

## Scope

**In scope:**
- `scraper/src/main/resources/ml/ml_train.py` — all four fixes
- `scraper/src/main/resources/ml/ml_pipeline.py` — Fix 1 (read img_size from checkpoint)

**Out of scope:**
- Changing model architectures or hyperparameters
- Adding new categories
- Frontend changes
- Java-side changes

---

---

## Part 2: Training Hangs (Why It Freezes)

After reading `PythonRunner.java` and `ApiController.java`, five distinct hang sources were found:

### Hang 1 — `tieneCuda()` blocks indefinitely (PythonRunner.java:253-261)
```java
String out = new String(pb.start().getInputStream().readAllBytes()).trim();
```
`readAllBytes()` blocks until the subprocess exits. `import torch` on Windows with a broken CUDA driver can hang for minutes or forever. There's no timeout and the `Process` object is discarded (can't kill it). The entire background training thread is stuck **before even starting the actual training**.

### Hang 2 — Sequential image downloads with no overall deadline (ml_train.py:274-314)
`download_images` downloads up to 3000 images one at a time:
```python
data = urllib.request.urlopen(req, timeout=5).read()
```
With `timeout=5` per request: 3000 images × worst-case = hours. During download, progress is emitted only every 20 images. To Java, the process appears frozen for minutes between updates.

### Hang 3 — `proc.waitFor()` has no timeout in both callers
- `PythonRunner.entrenarEnBackground` line 201: `proc.waitFor()` — no timeout
- `ApiController.mlEntrenar` line 816: `proc.waitFor()` — no timeout

If Python hangs for any reason (DataLoader deadlock, CUDA init, OOM), the Java thread blocks indefinitely with no way to recover.

### Hang 4 — No frontend-visible training state for background training
`mlTrainingRunning` (ApiController.java:842) only tracks training triggered via `POST /api/ml/entrenar`. The **background auto-training** launched by `PythonRunner.entrenarEnBackground` after each scrape has zero tracking — the user sees nothing and has no way to know it's running.

### Hang 5 — Two divergent code paths for the same operation
- **Auto training** (`entrenarEnBackground`): extracts `ml_train.py` from JAR classpath → correct
- **Manual training** (`mlEntrenar`): looks for `ml_train.py` in filesystem paths → fragile, breaks when running from a JAR without source

These two paths also differ in how they track progress and emit logs.

---

## Proposed Fixes for Hangs

### Fix 5: Add timeout to `tieneCuda()` / `tienePytorch()`
Store the `Process` reference and call `proc.waitFor(10, TimeUnit.SECONDS)` with `destroyForcibly()` on timeout.

### Fix 6: Parallel image downloads with thread pool
Replace sequential `urlopen` in `download_images` with `concurrent.futures.ThreadPoolExecutor(max_workers=8)`. Add a global deadline: if total download exceeds 3 minutes, stop and use what was collected. This alone can cut download time from hours to minutes.

### Fix 7: Add timeout to `proc.waitFor()` in both Java callers
Use `proc.waitFor(N, TimeUnit.MINUTES)` with `destroyForcibly()` on timeout. Text-only training: 10 min max. Image training: 120 min max.

### Fix 8: Expose background training status
Add a shared `AtomicReference<String> mlTrainStatus` in `PythonRunner` (or a service), exposed via `/api/ml/estado`. Both training paths update it. Frontend can poll.

### Fix 9: Unify both training code paths
`ApiController.mlEntrenar` should delegate to `PythonRunner.entrenarEnBackground(dbPath, true)` instead of duplicating subprocess management. One path, one source of truth.

---

## Scope (Updated)

**In scope:**
- `scraper/src/main/resources/ml/ml_train.py` — Fixes 1-4, 6
- `scraper/src/main/resources/ml/ml_pipeline.py` — Fix 1
- `scraper/src/main/java/ar/scraper/ml/PythonRunner.java` — Fixes 5, 7, 8, 9
- `scraper/src/main/java/ar/scraper/web/ApiController.java` — Fix 9

**Out of scope:**
- Model architectures or hyperparameters
- New categories
- Frontend changes beyond what `/api/ml/estado` already serves
- DB schema changes

---

## Risk

Medium. The Java changes touch subprocess management (correctness risk if timeouts are too short) and the training code path (regression risk if the unified path differs in behavior). The Python changes are low-risk. Existing trained models continue to work — `img_size` fallback to 224 for old checkpoints. Training must be re-run after the fix.
