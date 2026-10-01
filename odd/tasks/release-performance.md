# Release performance — measure before tagging

## Objective

Measure end-to-end performance, load capacity and production-like behavior of `master`
after backend-hardening, against the pre-hardening baseline `912d19a`, so the user can
decide on a tag and release with numbers.

## Problem

The backend-hardening report had only one performance number (`/api/grupos` 0.347 s ->
0.016 s); no latency at load, capacity or runtime behavior was measured after the change.

## Constraints

- No real production deployment exists: the app is installed locally (Windows portable,
  POSIX, Docker). "Production-like" = the `docker compose` stack, one of the shipped installs.
- A/B on the same machine, same dev database, same catalog, run one after the other.
  Each version uses its own perf harness (they differ only in reading the envelope and
  the first-page index).
- Read-only load (perf suites are read-only by design). Cron jobs 3 and 4 disabled during
  every boot and restored after; disposable accounts created by the harness are removed.
- Tag and release are the user's decision after seeing the numbers.

## Tasks

- [x] P1 Merge #262 when CI is green
- [x] P2 API load A/B (Locust): baseline, expected load, stress, spike — `912d19a` vs `master`
- [x] P3 End-to-end browser timing A/B: login -> catalog rendered, N runs, cross-origin preview build
- [x] P4 SSE capacity (`master` only): N concurrent `/api/events` streams — memory, threads, latency of other endpoints
- [x] P5 Production-like soak: `docker compose` stack, expected load for 30 min — heap/RSS over time, CPU, error log, boot time
- [x] P6 Report; tag/release decision goes to the user

## Acceptance

- Every number states version, machine, catalog size, scenario and run count.
- Regressions are reported as such, not averaged away.

## Checks

TDD: not applicable (measurement only, no source change planned). If a measurement exposes
a defect, it is reported and fixed only with user authorization.

## Progress

**P1:** #262 merged as `051899e` (CI CLEAN), branch deleted.

**Setup (all runs):** one 12-core Linux box; backend jar on JRE 21 default flags; Locust client on the same box; dev Postgres 16 (`fashion-scraper-pg`), 22,141 active products; cron jobs 3 and 4 disabled during every boot and restored after. `base` = `912d19a` (pre-hardening), `master` = `051899e`. Each version: boot, one discarded 30 s warm-up, then its own harness (`tests/perf/locust`). Order: base, master, then base baseline again (drift).

**Found while setting up:** `tests/perf/perf-user.sh` read `accessToken` at the JSON root; T6 moved it under `data`, so a fresh clone could not create the perf account. Fixed to read `data.accessToken` with a root fallback (works against both versions).

**P2 results** (p95 ms, base → master; 0 failures in every scenario for both):

| scenario | aggregated p95 | notable endpoints |
|---|---|---|
| baseline, 1 user | 130 → 56 (−57%) | grupos 150 → 4, mejores 25 → 6, marcas 13 → 5; data/recomendados/pcs_builder unchanged |
| login, 10 users | 52 → 40 (−23%) | — |
| expected load, 20 users, 3 min | 120 → 57 (−52%) | grupos 150 → 4, recomendados 94 → 80, data 33 → 29 |
| stress, ramp to 200 users, 5 min | 140 → 66 (−53%) | data 50 → 30, recomendados 150 → 99, pcs_builder 69 → 41 |
| spike, 150 users at once, 2 min | 130 → 63 (−52%) | grupos 170 → 6; `data` p99 58 → 200 in this run, see the spike re-runs below: not a regression |

Drift: base baseline re-run after master gave aggregated p95 140 vs 130 the first time, per-endpoint ±5 ms. Neither version saturated at 200 users (throughput linear, ~0.78 req/s per user = the think time), so the suite's stress does not answer capacity; a separate ramp to 2000 users runs in P4b. Caveat: the harness repeats the same filters, so the snapshot caches hit almost always; real users varying filters will see less of the grupos/marcas/mejores gain.

**P3 browser end to end** (Chromium headless, cross-origin `vite preview` of each version's bundle on :5173 against its backend; fresh context per iteration = first visit; 3 warm-up + 20 measured):

| | base p50 / p95 | master p50 / p95 |
|---|---|---|
| login click -> first product card | 223 / 249 ms | 220 / 237 ms |
| reload with session -> first product card | 144 / 159 ms | 144 / 154 ms |
| API requests: login / reload / 20 s idle | 13 / 12 / 0 | 14 / 13 / 0 |

No user-visible change: the catalog screen waits on `/api/data` (SQL, unchanged). The extra request is `/api/events`. Idle was already 0 requests before: the old status poll only ran during a scrape.

**P4 SSE capacity** (`master`; N streams opened at once with one token, each must receive `snapshot`; then 200 sequential `/api/status`):

| N | opened in | snapshots | `/api/status` p50 / p95 with streams | RSS | OS threads |
|---|---|---|---|---|---|
| 100 | 0.06 s | 100 | 1.4 / 4.7 ms | +9 MB | 58 -> 89 |
| 500 | 1.1 s | 500 | 1.1 / 1.6 ms | +25 MB | 86 -> 168 |
| 1000 | 1.16 s | 1000 | 1.1 / 1.4 ms | +76 MB | 165 -> 257 |
| 2000 | 0.82 s | 2000 | 0.9 / 1.5 ms | +238 MB | 257 -> 260 |

0 errors at every N; open streams cost memory (~120 KB each at 2000), not latency; OS threads plateau (pumps are virtual threads). The limit was not found at 2000.

**P4b capacity** (same `CatalogoUser` as the suite, think time 0.5–2 s, ramp to 2000 users at 10/s, 260 s, Locust with 4 processes on the same box):

| | base | master |
|---|---|---|
| throughput ceiling | ~200 req/s (from ~490 users) | ~380 req/s (from ~740 users), 1.9x |
| users when aggregated p95 > 1 s | 380 | 720 |
| requests served in the run | 50,140 | 88,681 (+77%) |
| failures | 0 | 0 |
| max JVM RSS | 3,917 MB | 1,339 MB |

Both degrade by queueing, never by refusing. Caveats: load average reached 23 on 12 cores with client, Postgres and JVM on one box, so master's ceiling may be the machine's, not the app's; the CPU column of the sampler is `ps` lifetime average, not instantaneous, so it is not reported.

**Spike re-runs** (the first spike showed `data` p99 58 → 200 ms): two more spikes per version, same conditions. `data` p99 across the three runs: base 58 / 130 / 290 ms, master 200 / 330 / 63 ms. The distributions overlap: an intermittent tail of `data` under a spike that already existed, not a regression. Aggregated spike p95 in the re-runs: base 210 / 210 ms, master 96 / 93 ms; 0 failures in all six.

**P5 production-like soak** (`docker compose -p scrappy-perf` with a throwaway env file; dev database copied with `pg_dump | pg_restore`, cron jobs disabled in the copy; expected load = the suite's 20-user scenario, 10 rounds x 3 min; 50 SSE streams held for the whole 30 min in 3 batches of ~10 min, since the server closes a stream at 10 min):

- Images built in 68 s; backend answered `GET /` 6.6 s after container start; frontend :8080 200.
- Backend memory: 575 MiB in minute 0, 615 MiB from minute 10 to minute 30, max 617 MiB of the 3 GiB `mem_limit`: flat, no leak trend. Postgres 126 -> 122 MiB.
- Latency per round: aggregated p95 55–58 ms in all 10 rounds (27,800 requests), `data` p95 16–22 ms; 0 failures. No degradation over time.
- SSE: 150 / 150 snapshots, 0 errors.
- Backend log: 0 ERROR, 2 WARN (the known `UserDetailsServiceAutoConfiguration` notice, and run 35, see below).
- Backend image is 4.24 GB (frontend 66 MB); not compared with base.
- Stack removed afterwards (`down -v --rmi local`).

**Incident caused by this measurement:** during a trial run the driver's `stop` killed the subshell, not the JVM; the trial's EXIT trap then re-enabled the cron jobs while that orphan `master` backend was still up, and its scheduler started the overdue job 4 as a real scrape (run 35, 2026-10-01 01:50:14 UTC). It ran ~21 s against fullh4rd, maximus, compragamer, rockethard and venex before the JVM was killed. It wrote nothing to the catalog: 0 `productos` touched or created since 01:50 UTC, 22,141 active before and after, no `precio_historico` rows. Run 35 was discarded with the same SQL as the app's discard endpoint (CANCELLED, its 5 sites SKIPPED). `cron_executions` 34 stays `running`, the same orphan the earlier sessions left as 32 and 33: a killed process never closes its cron execution. The driver then took the java pid via `pgrep` and refused to boot when :3000 was already answering.

## Summary (P6)

| question | answer |
|---|---|
| API latency at expected load | p95 −52% (120 -> 57 ms); every endpoint equal or better |
| browser, login -> catalog | unchanged (≈220 ms); the screen waits on SQL `/api/data` |
| capacity on this box | ~200 -> ~380 req/s (1.9x); p95 crosses 1 s at 380 -> 720 users; 0 failures either way; JVM RSS at saturation 3.9 -> 1.3 GB |
| live status | 2000 SSE streams: 0 errors, no latency cost, ~120 KB each |
| sustained, production-like | 30 min in `docker compose`: flat memory, flat p95, 0 failures, 0 ERROR |
| regressions found | none confirmed; the `data` spike tail exists in both versions |

Not measured: a real production deployment (none exists), other hardware (the Windows portable target), more than 30 min.

## Next step

User decision: tag and release (no tag or release exists yet; `pom.xml` and `package.json` say 1.0.0).
