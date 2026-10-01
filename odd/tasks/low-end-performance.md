# Low-end performance — the app runs, and runs fast, on old PCs and phones

## Objective

Make the app load, render and respond fast on phones from 2020 onwards (entry-level to
flagship) and on old PCs, and make sure it **runs at all** on those PCs, with the code optimized end to end (bundle, render, network,
API, JVM) under the same ODD rules as backend-hardening.

## Problem (measured 2026-10-01, master 059e3a9 = v1.0.0)

- **Old desktop browsers cannot run the bundle.** `vite.config.js` sets no `build.target`,
  so Vite 8.3 uses `baseline-widely-available` = Chrome/Edge 111+, Firefox 114+,
  Safari/iOS 16.4+ (Vite docs, v8.0.10). A PC browser older than early 2023 gets a broken
  app. (2020+ phones are expected to be inside that target; see Target devices.) The main CSS also uses `oklch` / `color-mix` /
  `@property` (to verify which, and from where).
- **JavaScript before the catalog renders:** `index.html` loads 152 KB gzipped of eager +
  modulepreload JS (index 83 KB, `tslib.es6` 43 KB on every page, AuthProvider, lucide,
  ProductCard...) before the route chunks. Total JS 753 KB gz; the largest lazy chunks are
  `ApiDocsPanel` (swagger-ui, 385 KB gz) and `PriceHistoryPage` (recharts, 96 KB gz).
- **framer-motion 11** is imported by 8 components, including the product grid path.
- **No compression seen:** no `server.compression` in `application.properties`, no `gzip`
  in `frontend/nginx.conf`. `/api/data` returns ~28 KB per 24-product page (to verify on
  the wire: Content-Encoding).
- **Browser end to end today** (desktop Chromium, no throttling, fast box): login -> first
  product card 220 ms p50; reload with session -> card 144 ms p50. **Never measured with a
  slow CPU or a slow network**, which is the whole point of this feature.
- **Backend at expected load:** aggregated p95 57 ms; the slow endpoints are SQL:
  `recomendados` ~80 ms, `data_filtrado` ~43 ms, `data` ~29 ms p95. JVM RSS ~600 MiB idle
  under load; backend Docker image 4.24 GB. Old PCs also host the backend (portable/POSIX
  installs), so JVM + Postgres + Python memory is part of "runs on an old PC".
- Reference numbers and harnesses: `odd/tasks/release-performance.md`, `tests/perf/`.

## Why

Users on old hardware get the slowest experience or none at all; every number above was
measured on a fast 12-core box.

## Target devices

**Phones — decided by the user 2026-10-01:** not "old phones" but good performance on
phones from 2020 onwards, entry-level to flagship. The profile to optimize for is a 2020
entry-level Android (Galaxy A11 / Moto E7 class: entry SoC, 2–3 GB RAM); flagships must
not regress. Measurement proxy: Lighthouse mobile defaults (4x CPU slowdown, Slow 4G
150 ms RTT / 1.6 Mbps) plus a 6x CPU run for the slowest SoCs.

Browser floor on phones is expected to be a non-issue, **to verify in T0, not assumed**:
Android Chrome updates through the Play Store and those devices shipped Android 10+; every
iPhone from 2020 (SE 2nd gen onwards) can run iOS 16.4+, which is inside Vite's default
target (Safari/iOS 16.4+).

**PCs — still PROVISIONAL, confirm with the user before T1:**

| profile | proposal |
|---|---|
| old PC (browser) | 2 cores, 4 GB RAM, Chrome 90 / Firefox 90 |
| old PC (host of the portable install) | 4 GB RAM total for JVM + Postgres + Python + browser |

The PC browser floor decides whether the build target must go below Vite's default
(`build.target` and/or `@vitejs/plugin-legacy`); it is a product decision.

## Constraints (same rules as backend-hardening)

- ODD: one feature document (this file) + Engram mirror `odd/low-end-performance/tasks`;
  update both after each task; check a task only with observed proof.
- **Measure first, every task:** a before number and an after number from the same harness,
  on the target profile, with its cost (bytes, ms, MB, lines). No optimization lands
  without a measured gain on a target profile; a gain only on the fast box does not count.
- Strict TDD where behavior changes (RED observed, GREEN, REFACTOR). A pure refactor keeps
  the existing tests untouched (`CODE-2`, memory "refactor-contract").
- `CONTRIBUTING.md` rules apply (`COMMIT-*`, `TEST-1` green on every commit with `clean`,
  `DOC-1/2`, `PR-1..4`); no Co-Authored-By; work-unit commits; stacked PRs per task.
- "Super optimized" means measured and minimal: no gratuitous abstraction, no
  micro-optimization without a number, no deleting tests/comments to look smaller.
- Functionality and the API contract stay; any user-visible change (e.g. fewer animations
  on slow devices) is proposed to the user first.
- Boot checks: disable cron jobs 3 and 4 before any backend boot on the dev DB and verify
  the JVM is dead before re-enabling (memory "dev-boot-starts-overdue-cron-scrape").

## Tasks (ordered by expected impact on a low-end device; re-rank after T0)

- [ ] T0 Baseline on the target profiles: Lighthouse mobile + desktop (throttled) for
  `/login`, `/catalogo`, one armador; CDP-throttled Playwright timing (login -> first card,
  reload -> first card, scroll 3 pages, open detail); bundle report per route; bytes on the
  wire with/without compression; runs-or-not on the browser floor
- [ ] T1 PC browser floor: set the build target / legacy plugin for the confirmed PC floor;
  fix any CSS that floor cannot render (`oklch`, `color-mix`, `@property`); prove it runs
  there. Skipped if the confirmed PC floor is already inside Vite's default target
- [ ] T2 Compression: gzip/brotli for the SPA (nginx + preview) and the API (Spring), with
  the SSE stream excluded from buffering; measure bytes and time on Slow 4G
- [ ] T3 Critical-path JS: cut what `/login` and `/catalogo` load before first paint
  (`tslib` origin, framer-motion off the grid path, lucide per-icon imports, route-level
  splitting); budget per route in KB gz, enforced by a test
- [ ] T4 Render cost on a 4x-6x slower CPU: product grid (memo, `content-visibility`, or
  windowing if measured necessary), animations gated by `prefers-reduced-motion` and by
  device capability; INP/long tasks measured
- [ ] T5 Images: thumbnails at the size the card shows (store CDNs that support resize
  params), `decoding="async"`, explicit dimensions to avoid layout shift
- [ ] T6 API payload: fields the grid does not use, page size vs. device, caching headers
- [ ] T7 SQL hot paths: `recomendados`, `data_filtrado`, `facets` (EXPLAIN ANALYZE first)
- [ ] T8 Old-PC host footprint: JVM heap/flags for 4 GB machines, Python/ML memory, Postgres
  settings for the portable install; backend image size
- [ ] T9 Close: re-measure everything from T0 on the same profiles; report; regressions
  reported as such

## Acceptance

- Runs on the confirmed browser floor (proved, not assumed).
- Per-route JS budgets and Lighthouse budgets enforced by tests/CI where cheap.
- Every task carries before/after numbers on a target profile.

## Checks

- Backend: `mvn clean test` (JDK 24 compiles, JRE 21 runs) — 3259 / 0 / 0 / 7 at v1.0.0.
- Frontend: `npm test` — 55 files / 514 tests at v1.0.0; `npm run build` with
  `VITE_API_BASE_URL`.
- E2E: `tests/e2e/run-e2e.sh` (never `vite dev`) — 51 API + 31 browser at v1.0.0.
- Perf: `tests/perf/locust` (`uv run pytest`, `-m lento`).
- TDD: strict (session configuration); runner per layer as above.

## Progress

Planned 2026-10-01 from a short exploration (bundle sizes, build target, compression
config); nothing implemented yet.

## Next step

T0 (phone profile is decided). Confirm the PC profile with the user before T1.
