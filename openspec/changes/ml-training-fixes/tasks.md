# Tasks: ML Training & Classification Fixes

**Change:** ml-training-fixes  
**Date:** 2026-06-16  
**Status:** tasks

---

## Batch 1 — ml_pipeline.py (AD1, AD3)

- [ ] **T1.1** Add module-level `_IMG_SIZE: int = 224` and `_TEXT_LABEL_SET: frozenset = frozenset()` at the top of `ml_pipeline.py`, after imports.

- [ ] **T1.2** In `load_trained_models`, after loading the image checkpoint, read `checkpoint.get("img_size", 224)` and assign to the global `_IMG_SIZE`. Log the value: `[ML] img_size={_IMG_SIZE} (from checkpoint)`.

- [ ] **T1.3** In `load_trained_models`, after loading `le` (text label encoder), assign `_TEXT_LABEL_SET = frozenset(str(c) for c in le.classes_)`.

- [ ] **T1.4** If both models are loaded, log the label sets intersection/difference: `[ML] text_labels={len(_TEXT_LABEL_SET)}, img_labels={len(img_le.classes_)}, overlap={len(frozenset(img_le.classes_) & _TEXT_LABEL_SET)}`.

- [ ] **T1.5** In `predict_category_image`, replace the hardcoded `T.Resize((224, 224))` with `T.Resize((_IMG_SIZE, _IMG_SIZE))`.

- [ ] **T1.6** In `load_trained_models`, if `img_size` key is absent from checkpoint, log: `[ML] WARN: checkpoint sin img_size, usando fallback 224`.

- [ ] **T1.7** In the ensemble section of `main()` (around line 775), after `img_cat, img_conf = predict_category_image(...)`, add guard:
  ```python
  if img_cat and _TEXT_LABEL_SET and img_cat not in _TEXT_LABEL_SET:
      img_cat, img_conf = None, 0.0
  ```

---

## Batch 2 — ml_train.py (AD2, AD4, AD5)

- [ ] **T2.1** Add `from PIL import Image` import at the top of `ml_train.py` (it's already used via `from PIL import Image, UnidentifiedImageError` inside `train_image_model` — hoist it to module level so `load_image_dataset` can use it).

- [ ] **T2.2** Add module-level `_download_one` function (required for `ThreadPoolExecutor` pickling on Windows):
  ```python
  def _download_one(args):
      """args = (prod_url, img_url, img_dir_str). Returns (fpath_str, ) or None."""
      prod_url, img_url, img_dir_str = args
      import urllib.request, hashlib
      from pathlib import Path
      from PIL import Image
      img_dir = Path(img_dir_str)
      if img_url.startswith("//"): img_url = "https:" + img_url
      if not img_url.startswith("http"): return None
      fname = hashlib.md5(prod_url.encode()).hexdigest() + ".jpg"
      fpath = img_dir / fname
      # Validate cached file
      if fpath.exists():
          try:
              with Image.open(fpath) as im: im.verify()
              return str(fpath)
          except Exception:
              fpath.unlink(missing_ok=True)
      # Download
      try:
          req = urllib.request.Request(img_url, headers={'User-Agent': 'Mozilla/5.0'})
          data = urllib.request.urlopen(req, timeout=5).read()
          with open(fpath, 'wb') as f: f.write(data)
          # Validate after download
          with Image.open(fpath) as im: im.verify()
          return str(fpath)
      except Exception:
          if fpath.exists(): fpath.unlink(missing_ok=True)
          return None
  ```

- [ ] **T2.3** Rewrite `download_images(db_path, img_dir, max_images=3000)` to:
  1. Keep the same SQL query and DB connection logic
  2. Replace the sequential loop with `ThreadPoolExecutor(max_workers=8)`
  3. Enforce a `GLOBAL_DEADLINE_S = 180` wall-clock deadline using `time.monotonic()`
  4. Emit `progress("image_download", pct, ...)` every 50 futures completed
  5. At the end log: total attempted, OK, failed, elapsed seconds
  6. Return `[(fpath_str, cat)]` — same shape as before

- [ ] **T2.4** Add `load_image_dataset(db_path, img_dir, max_images=3000)` function that:
  1. Runs same SQL as current `download_images` but also selects `nombre`
  2. For each row applies `corregir_etiqueta(nombre, cat)` 
  3. Counts category frequencies and applies `CAT_PARENTS` merging (same logic as `clean_labels`, but operating on `(img_url, cleaned_cat)` pairs)
  4. Returns `[(prod_url, img_url, cleaned_cat)]` — the input for `download_images`

- [ ] **T2.5** In `train_image_model`, replace the current call to `download_images(db_path, img_dir)` with:
  ```python
  raw_rows = load_image_dataset(db_path, img_dir)
  samples  = download_images(db_path, img_dir, raw_rows)
  ```
  Where `download_images` is updated to accept `rows` as parameter instead of re-querying the DB.

- [ ] **T2.6** Update `download_images` signature to `download_images(img_dir, rows, max_images=3000)` — it no longer needs `db_path` since `load_image_dataset` already fetched the rows. Remove the internal DB query.

---

## Batch 3 — PythonRunner.java (AD6, AD9)

- [ ] **T3.1** Add `TrainingStatus` as a public nested record inside `PythonRunner`:
  ```java
  public record TrainingStatus(
      boolean running, String phase, int pct, String msg, String startedAt) {
      public static TrainingStatus idle() {
          return new TrainingStatus(false, "idle", 0, "", null);
      }
  }
  ```

- [ ] **T3.2** Add field: `private final AtomicReference<TrainingStatus> trainingStatus = new AtomicReference<>(TrainingStatus.idle());`

- [ ] **T3.3** Add public accessors:
  ```java
  public TrainingStatus getTrainingStatus() { return trainingStatus.get(); }
  public boolean isTrainingRunning()        { return trainingStatus.get().running(); }
  ```

- [ ] **T3.4** Fix `tieneCuda(python)`:
  - Store `Process proc = pb.start()`
  - Use `proc.waitFor(10, TimeUnit.SECONDS)` — if timeout, call `proc.destroyForcibly()` and return `false`
  - Read stdout only after `waitFor` confirms exit: `proc.getInputStream().readAllBytes()`

- [ ] **T3.5** Fix `tienePytorch(python)` — same timeout pattern as T3.4.

- [ ] **T3.6** Change `entrenarEnBackground` signature to:
  ```java
  public void entrenarEnBackground(String dbPath, boolean forceRetrain,
                                    boolean withImages, int epochs)
  ```
  Keep the two existing overloads as delegation bridges.

- [ ] **T3.7** At the start of `entrenarEnBackground`, set:
  ```java
  trainingStatus.set(new TrainingStatus(true, "starting", 0, "",
      java.time.Instant.now().toString()));
  ```

- [ ] **T3.8** In the stdout-reading loop of `entrenarEnBackground`, when a `{"phase":..., "pct":..., "msg":...}` JSON line is parsed, update:
  ```java
  trainingStatus.set(new TrainingStatus(true, ph, pct, msg,
      trainingStatus.get().startedAt()));
  ```

- [ ] **T3.9** On completion (exit code 0), set `trainingStatus` to `idle()`. On timeout, set phase to `"timeout"`. On error/non-zero exit, set phase to `"error"`.

- [ ] **T3.10** Replace `proc.waitFor()` (line 201) with:
  ```java
  boolean withImg = cmd.contains("--images");
  long timeout = withImg ? 180L : 15L;
  boolean finished = proc.waitFor(timeout, TimeUnit.MINUTES);
  if (!finished) {
      proc.destroyForcibly();
      LOG.error("[ML-TRAIN] TIMEOUT tras {} min — proceso terminado forzosamente", timeout);
      trainingStatus.set(new TrainingStatus(false, "timeout", 0, "", null));
      return;
  }
  ```

- [ ] **T3.11** Wire `withImages` and `epochs` into the command list:
  ```java
  if (withImages) { cmd.add("--images"); cmd.add("--epochs"); cmd.add(String.valueOf(epochs)); }
  ```

---

## Batch 4 — ApiController.java (AD7, AD8)

- [ ] **T4.1** Inject `PythonRunner pythonRunner` into `ApiController` constructor (it's already a Spring bean — add it to the constructor params and store it as a field).

- [ ] **T4.2** Replace the body of `mlEntrenar` with delegation to `pythonRunner`:
  ```java
  if (pythonRunner.isTrainingRunning())
      return ResponseEntity.badRequest().body(Map.of("error", "Entrenamiento ya en curso"));

  String dbPath = encontrarDbFile().getAbsolutePath();
  pythonRunner.entrenarEnBackground(dbPath, true, images, epochs);
  return ResponseEntity.ok(Map.of("status", "started"));
  ```

- [ ] **T4.3** Remove private fields `mlTrainingRunning` and `mlTrainingResult` from `ApiController`.

- [ ] **T4.4** Remove private methods `localizarPython()` and `localizarScript(String)` from `ApiController`.

- [ ] **T4.5** Update `mlResultado()` to read from `pythonRunner.getTrainingStatus()`:
  ```java
  var s = pythonRunner.getTrainingStatus();
  return ResponseEntity.ok(Map.of(
      "running", s.running(),
      "phase",   s.phase(),
      "pct",     s.pct(),
      "msg",     s.msg(),
      "done",    !s.running() && !"idle".equals(s.phase())
  ));
  ```

- [ ] **T4.6** Extend `mlEstado()` to include the training status block:
  ```java
  var ts = pythonRunner.getTrainingStatus();
  ObjectNode training = root.putObject("training");
  training.put("running",    ts.running());
  training.put("phase",      ts.phase());
  training.put("pct",        ts.pct());
  training.put("msg",        ts.msg());
  training.put("startedAt",  ts.startedAt() != null ? ts.startedAt() : "");
  ```

---

## Task Summary

| Batch | File | Tasks | Scope |
|-------|------|-------|-------|
| 1 | `ml_pipeline.py` | T1.1–T1.7 | IMG_SIZE fix + label guard |
| 2 | `ml_train.py` | T2.1–T2.6 | Clean labels + parallel downloads + cache validation |
| 3 | `PythonRunner.java` | T3.1–T3.11 | TrainingStatus + timeouts + unified path |
| 4 | `ApiController.java` | T4.1–T4.6 | Delegate + extend estado |

**Total: 28 tasks across 4 files.**  
All batches are independent — Batch 1 and 2 can be applied without Java changes. Batch 4 depends on Batch 3.
