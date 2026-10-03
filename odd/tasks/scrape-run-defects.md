# Scrape-run defects — #285, #286, #287

## Objective

Fix the three defects found on the full scrape of 2026-10-02 (run 40, master 87ef628), on branch
`docs/known-issues-scrape-run` (PR #288, which so far only documents them). User asked: "fix em in this branch".

## Findings (verified in code + dev DB, 2026-10-02)

- **#285** `ScraperService` (~l.446–557): `progSitios` and `idxMap` are keyed by `site.nombre()` = site key
  (`vcp`), but the finished-site callback looks up `r.sitio()` = display name (`Vcp`) → `idx = -1`, progress never
  goes DONE; after the loop every site still EN_CURSO gets `actualizarProgreso(ERROR)`, an empty
  `ScrapeResult(..., "Deadline global")` and `registrarSitioTerminado(key, "ERROR", 0, "Deadline global")`.
  Run 40: `scrape_run_site` 29/29 ERROR, 0 products. Since 2026-09-28. Check before fixing: `marcarSitioTerminadoSql`
  matches `sitio_key = %s` (some key-normalizing SQL) — the DONE write may land and then be overwritten.
  Confirm soft-delete scope (`alcanceDelRun`) does not depend on status.
- **#286** Final `[DB] Upsert: 0 nuevos / 0 precio cambió` is the residue: each site is persisted by
  `ProductRepository.upsertParcial` as it finishes (no stats logged), so the final `upsertProductos` sees no changes.
  Run 40 really wrote 616 new rows + 1861 `precio_historico`. Fix: `upsertParcial` returns its `UpsertStats`
  (sp_upsert_run already computes them), accumulate per run in `ScraperService`, log the run total.
  CLAUDE.md rule: every upsert test asserts `nuevos()` first.
- **#287** `[ML-TRAIN]` exits 1: `ModuleNotFoundError: numpy` (`ml_train.py:32 import numpy as np`). Scoring and
  training use the SAME interpreter (`PythonRunner.detectarPython()`: `-DPYTHON_EXE` → `_tools/python/...` →
  `python3`/`python` on PATH). `ml_pipeline.py` imports only stdlib, so scoring works on a bare python3; training
  needs numpy + sklearn (+ psycopg2). Found: `Ejecutar_instalar.sh` never installs ML deps (only `_tools/cli-venv`
  with cli/requirements.txt); Windows .bat installs numpy/scipy/scikit-learn/Pillow; Dockerfile installs torch +
  open_clip + psycopg2 but NOT scikit-learn (unverified at runtime). On this POSIX dev setup it resolved to system `python3`. Open question: fix = point
  training at a venv with the ML deps (how does the installer provide them? check `Ejecutar_instalar.sh`,
  `_tools/`, requirements files), or a pre-flight import check that logs once and skips. Likely needs a user decision.

## Checks

- Strict TDD (global): RED observed before each fix (`CODE-1`). Runner (always `clean`, check exit + grep):
  `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`
  Baseline master 87ef628: 3393 / 0 F / 0 E / 7 skipped.
- Real proof: a full scrape (disable crons 3,4 first, restore after; driver used on 2026-10-02 in the old session's
  scratchpad `full-scrape.sh`: env = `tests/e2e/.e2e-secrets.env` + `DATABASE_URL=jdbc:postgresql://localhost:5432/scraper`,
  `DATABASE_USERNAME=postgres`, empty password, `APP_CORS_ALLOWED_ORIGINS`), then
  `select status,count(*),sum(productos_count) from scrape_run_site where scrape_run_id=<id> group by 1`.
- Use commons-lang3 over hand-written null checks (user preference). Conventional commits, no AI attribution.
- Update `docs/KNOWN_ISSUES.md` rows (added in a77ea50) when each is fixed; PR #288 title/body to cover the fixes.

## Tasks

- [x] T1 #285 site key vs display name — RED test (two sites whose display name ≠ key), fix, real-scrape proof.
  Done: `IndiceDeSitios` matches by `SiteClassification.sitioKey`; RED observed (`"Vcp"` → -1), suite 3396/0/0/7.
  Proof run 41: scrape_run_site 29/29 DONE, 25831 products (run 40: 29/29 ERROR, 0). Commit 726ee25.
- [x] T2 #286 run-level upsert stats — RED test, fix. `upsertParcial` returns `UpsertStats`, `UpsertStats.sumar`,
  run line `[DB] Por sitio: …` after `[FIN]`. RED observed on both new tests; suite 3399/0/0/7.
  Run 41: `[DB] Por sitio: 58 nuevos / 80 precio cambió / 25693 sin cambio`; DB: 58 productos created in the run window. Commit 8ce8850.
- [x] T3 #287 ML-TRAIN interpreter — user chose BOTH (2026-10-02): provision + preflight.
  Done: `scraper/ml-requirements.txt` (numpy scipy scikit-learn Pillow psycopg2-binary); `Ejecutar_instalar.sh`
  builds `_tools/ml-venv` with uv (non-fatal); `PythonRunner.candidatosLocales` adds `../_tools/ml-venv/bin/python`
  and `_tools/ml-venv/bin/python` before PATH; `entrenarEnBackground` probes `import numpy, sklearn, psycopg2` and
  sets TrainingStatus phase `skipped` + WARN when missing; Dockerfile installs `-r ml-requirements.txt`.
  RED observed on 3 new tests; `PythonRunnerProcessLaunchTest` fake interpreter taught to answer the new probe
  (`*sklearn*) echo ok`, no assertion touched). Suite 3402/0/0/7. Installer step run by hand: venv OK, 257 MB.
  Run 41: trained on _tools/ml-venv (system python3 still lacks numpy): `✓ ENTRENAMIENTO COMPLETADO`, 96.2% / 98 classes.
  NOT verified: Docker image rebuild. Commit 46dfb3e.
  Note: CLI already has `retrain` → POST /api/ml/entrenar (construirIndiceVisualEnBackground, no preflight there).
- [~] T4 Docs (KNOWN_ISSUES rows → resolved), PR #288 title/body, CI + Sonar.
  Done: rows removed in each fix commit; two new KNOWN_ISSUES rows (cosmetic `Imagen : ERROR` line, unrotated backend.log).

## Progress

- 2026-10-02: issues #285–#287 filed, documented in PR #288 (a77ea50, 5f48fb8). User asked to fix them in this
  branch; context cleared before any code change. Next: T1.
- 2026-10-02: T1 code + T2 done (uncommitted). T3 decided.
- 2026-10-02: T3 code done (uncommitted). Next: full scrape proving T1+T2+T3 together, then T4.
- 2026-10-02: run 41 (21:19–21:40, 20m54s) proved T1+T2+T3; crons 3,4 restored. Next: push, PR #288 body, CI + Sonar.
