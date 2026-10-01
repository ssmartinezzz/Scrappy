# Low-end performance — the app runs, and runs fast, on old PCs and phones

## Objective

Make the app load, render and respond fast on phones from 2020 onwards (entry-level to
flagship) and on old PCs, and make sure it **runs at all** on those PCs, with the code optimized end to end (bundle, render, network,
API, JVM) under the same ODD rules as backend-hardening.

## Problem (measured 2026-10-01, master 059e3a9 = v1.0.0)

- **Old desktop browsers cannot run the bundle.** `vite.config.js` sets no `build.target`,
  so Vite 8.3 uses `baseline-widely-available` = Chrome/Edge 111+, Firefox 114+,
  Safari/iOS 16.4+ (Vite docs, v8.0.10). A PC browser older than early 2023 gets a broken
  app. (2020+ phones are expected to be inside that target; see Target devices.) The main CSS uses `color-mix` (54 times;
  verified in T0: no `oklch`, no `@property`).
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

**PCs — browser floor decided by the user 2026-10-01: Chrome 109** (the last Chrome on
Windows 7/8.1). T0 proved the current build already runs there; T1 pins `build.target` so
it cannot silently regress and gives `color-mix` a fallback. Chrome 90 was rejected (would
need a lower target or `@vitejs/plugin-legacy` for every user).

| profile | status |
|---|---|
| old PC (browser) | **Chrome 109**, 2 cores, 4 GB RAM |
| old PC (host of the portable install) | PROVISIONAL: 4 GB RAM total for JVM + Postgres + Python + browser — confirm before T8 |

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

## Tasks (ordered by expected impact on a low-end device; re-ranked after T0, see Progress)

Execution order after T0: **T5, T2, T6, T3, T1, T4, T10, T8, T7, T9**.

- [x] T0 Baseline on the target profiles: Lighthouse mobile + desktop (throttled) for
  `/login`, `/catalogo`, one armador; CDP-throttled Playwright timing (login -> first card,
  reload -> first card, scroll 3 pages, open detail); bundle report per route; bytes on the
  wire with/without compression; runs-or-not on the browser floor
- [ ] T1 PC browser floor = Chrome 109: pin `build.target` to it (no legacy plugin), give
  `color-mix` a fallback where the tint carries meaning, prove the built app runs on
  Chromium 109 (and keep failing loudly below the floor)
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
- [ ] T10 Catalog growth (user, 2026-10-01: "los registros de productos seguramente
  aumenten mucho si seguimos añadiendo pages"): on a throwaway copy of the dev DB grown to
  2x and 4x products, measure the hot endpoints' p95, boot time and JVM live heap vs. N;
  fix what grows faster than linearly; feeds the T8 heap sizing

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

### T0 baseline — done 2026-10-01

**Setup:** branch `perf/low-end-devices` (= master 059e3a9 + docs), backend jar on JRE 21
default flags, `vite preview` of a fresh `vite build` on :5173 (cross-origin, as shipped),
dev DB 22,141 active products, 12-core box. Cron jobs 3 and 4 disabled during the boot and
restored after the JVM was verified dead; 0 scrape runs started. Harnesses (scratchpad,
not committed): Playwright + CDP throttling (fresh context per iteration = cold cache,
1 discarded warm-up), Lighthouse 12.8.2 simulated throttling on system Chrome 154, median
of 3, cold cache.

**Browser timing, p50 (N = 10 fast, 5 per throttled profile):**

| step | fast box | mobile 4x + Slow 4G | mobile 6x + Slow 4G | old PC 4x, 40 ms / 10 Mbps |
|---|---|---|---|---|
| `/login` form visible | 92 ms | 1580 ms | 1626 ms | 445 ms |
| login click -> first card | 274 ms | 1920 ms | 1955 ms | 881 ms |
| reload with session -> first card | 217 ms | 1994 ms | 2133 ms | 1039 ms |
| scroll 3 pages (72 more cards) | 1064 ms | **21808 ms** | **21854 ms** | 2487 ms |
| click card -> detail open | 148 ms | 1384 ms | 1472 ms | 662 ms |
| TBT login->card / detail | 0 / 29 ms | 114 / 172 ms | 181 / 252 ms | 128 / 175 ms |

Mobile 4x and 6x are within 3% of each other on every step except TBT: **on phones the
bottleneck is the network, not the CPU.** DOM 3.6k nodes and JS heap 9–12 MB after 144
cards: render cost is not the problem today.

**Lighthouse, cold cache (median of 3):**

| route | mobile (4x, Slow 4G) | mobile 6x | desktop | old PC (desktop, 4x CPU) |
|---|---|---|---|---|
| `/login` | 97 · LCP 2.10 s · 171 KB | 97 · 2.10 s | 100 · 0.48 s | 100 · 0.48 s |
| `/catalogo` | **85 · LCP 4.00 s** · 968 KB | 85 · 4.04 s | 99 · 1.04 s · 2185 KB | 98 · 0.96 s |
| `/outfits` | 89 · LCP 3.56 s · 488 KB | 88 · 3.70 s | 99 · 0.94 s | 98 · 1.08 s |

TBT ≤ 98 ms and CLS ≤ 0.04 everywhere. Lighthouse `/catalogo` mobile opportunities:
responsive images −424 KiB, modern image formats −252 KiB, text compression −61 KiB,
unused JS −50 KiB, render-blocking −150 ms. Warm-cache repeat visit (same matrix with
`--disable-storage-reset`): `/catalogo` mobile 99, LCP 2.03 s.

**Where the bytes go (fast box, Pixel 5 viewport, catalog):**

| phase | JS | CSS | API | images |
|---|---|---|---|---|
| `/login` | 153 KB gz (10 files) | 16 KB | 1 KB | — |
| login -> first screen | 0 | 0 | 88 KB, 12 fetches + 13 CORS preflights | **711 KB, 16 images** |
| scroll ~100 cards | 0 | 0 | 112 KB | **5307 KB, 94 images** |

Images: p50 45 KB, p90 67 KB, max 524 KB; natural width p50 610 px for a 179 CSS-px card
(492 device px at DPR 2.75, so ~1.2x on that phone, ~3x on a DPR-1 PC). Largest hosts:
fullh4rd 2.1 MB, compragamer 1.5 MB, contabilium 0.96 MB, venex, maximus. `loading=lazy`
already, `decoding=auto`. **Images are 95% of the catalog bytes; the 21.8 s scroll is
images saturating Slow 4G.**

**Compression on the wire:** API sends no `Content-Encoding` (`/api/data` 24 items 28.7 KB
raw -> 6.5 KB gzip, 4.4x; `/api/grupos` 23.0 -> 4.2 KB; `/api/recomendados` 16.5 -> 2.5 KB)
and every API response is `no-store`. `vite preview` (portable/POSIX installs) gzips but
sends `Cache-Control: no-cache` on content-hashed assets (a revalidation round trip each
on repeat visits); the Docker `nginx.conf` has no gzip and no cache headers at all. CORS
`Access-Control-Max-Age: 1800` is set; preflights are per URL, so a first visit pays one
per endpoint.

**Bundle (vite build):** entry + modulepreload 153 KB gz on `/login` (index 83.6, `tslib.es6`
43.5, `jsx-runtime` 9.1, utils 6.9, `dist` 7.2, ProductCard 2.2, AuthProvider 0.5…), CSS
16 KB gz. Lazy: ApiDocsPanel 390 KB gz, PriceHistoryPage 97 KB gz, `proxy` (framer-motion)
36 KB gz, `checkbox` 24.6 KB gz. `/outfits` cold adds ~317 KB (chunks + images).

**Browser floor (proved by running the built app):**

| browser | result |
|---|---|
| Chromium 91.0.4472 | **broken**: `SyntaxError: Unexpected token '{'` before render — a class `static {}` block (Chrome 94+) from Radix `OrderedDict`, emitted in the entry chunk |
| Chromium 109.0.5414 (last Chrome on Windows 7/8.1) | **works**: login, 48 cards, `/outfits`, `/pcs` render; screenshot visually identical to current Chrome. `color-mix` (Chrome 111+) is used 54 times in the CSS; on 109 those declarations drop silently (tints/borders), no visible breakage in the checked screens |
| Chromium 111.0.5563 | works (Vite's default target) |

JS runtime APIs above Chrome 111: none in app code; `toSorted` (Chrome 110) is a Radix
`OrderedDict` method, `findLast`/`hasOwn` only in the swagger chunk. Phones: Android Chrome
is evergreen via the Play Store and every 2020 iPhone runs iOS 16.4+ (Vite's floor) — not
run on a real device or an old WebKit; verified by version arithmetic only.

**Host footprint:** JVM RSS 577 MiB idle after load, 57 threads, G1, default max heap
= 1/4 of RAM (3.9 GB here; **1 GB on a 4 GB PC**). Live heap after full GC **108 MB for
22,141 products** (top: 702k `LinkedHashMap$Entry`, 181k Jackson `DoubleNode`, 22,141
`Product`) — at most ~5 KB per product (the total includes the non-catalog baseline);
how it grows with N is T10's to measure. Postgres 131 MiB.

**Re-ranking (why the order changed):** images (T5) and compression (T2) carry the mobile
numbers; JS (T3) is 153 KB and already off the scroll path; render (T4) is small at 4x–6x
(TBT ≤ 252 ms, DOM 3.6k). With the floor at Chrome 109, T1 shrinks to pinning the target and a `color-mix` fallback. T10 added
by the user; T7 (SQL ~30–80 ms server-side) is invisible next to 150 ms RTT and seconds of
images, so it moves after T10, which may change that.

## Next step

PC browser floor confirmed (Chrome 109). Next: T5 images. Host RAM profile (4 GB) to
confirm before T8.
