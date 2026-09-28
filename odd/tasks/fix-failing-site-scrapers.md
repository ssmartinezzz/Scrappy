# Fix the failing site scrapers — harvey, foreverbstrd, sporting, fullh4rd

> **Objective.** The four sites that scrape zero, get truncated, or are abandoned
> in every full run come back with their real catalog, and a slow site is never
> again left `RUNNING` in a run that closed as `COMPLETED`.
>
> User request (2026-09-28): *"se que algunos sitios estan fallando en los
> scrappings, podes revisar y vemos alguna strategy para fixearlos"* →
> *"arreglemos todos, pero con SOLID y POM"* → accepted recommendation: a
> dedicated page object only where a site diverges from its platform (Fullh4rd);
> no empty per-site subclasses.
>
> TDD mode: **on** (`CODE-1`, session Strict TDD). Runner:
> `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`
> (`TEST-2`; `-Dtest=...` while iterating, full `clean test` before each commit).

## Problem — measured on runs 20, 23, 27 and the live sites (2026-09-28)

| Site | Symptom | Verified cause |
|---|---|---|
| harvey | `RUNNING:0` in every full run since 09-12; 0 rows in `productos` | (a) `ScraperService.java:539`: the `[ESPERA]` timeout `continue`s, which burns a `for` iteration; the loop ends after `totalSitios` iterations with Harvey still in flight, `registrarSitioTerminado` never runs. (b) `urls_extra` `/otras-temporadas1?mpage=N` serves the **same 18 products on every mpage** (server ignores it) → 60 identical pages. `/otras-temporadas1/?page=N` paginates (18/page, ends before p40). |
| foreverbstrd | 0 in every run | `/collections/all` is a 404. `/productos/` serves TN cards. |
| sporting | 2491 → 250 today | `VtexPage.java:132` breaks silently on any non-JSON body; each page (7-9 MB) goes through `navigateTo` + `innerText`. `MAX_PRODUCTS = 2500` caps coverage: the API reports 7151 and answers past `_from=2500`. |
| fullh4rd | 844-1119, ERROR in run 23 | Site redesigned. `/productos?page=N` lists all **1918** (12/page, p160 = 10, p161 = 0) as `article.results-card`. The hardcoded `FH_CATS` over `/cat/supra/` misses CPUs, motherboards, power supplies. A page error breaks a whole category, logged at DEBUG. (Run 23's ERROR was a backend shutdown, not the site.) |

## Scope

- In: the five fixes below, their tests, `docs/SITES.md` / `docs/GOTCHAS.md` updates.
- Out: new `plataforma` values or migrations; per-site classes with nothing to override; the global price band.

## Tasks

- [x] **T1 — Run wait loop never abandons a site.** A per-site wait timeout must not consume a result slot; a site still unfinished at the global deadline (or when the loop gives up) is registered `ERROR` with a timeout reason, never left `RUNNING`. Test reproduces "one slow site, all others done".
- [x] **T2 — foreverbstrd URL.** `config.properties` → `https://foreverbstrd.com/productos/`; `docs/SITES.md` row updated.
- [x] **T3 — Harvey outlet + TN repeat guard.** `urls_extra` → `https://www.harveywillys.com/otras-temporadas1/`; remove the `mpage` special case from `TiendanubePage` (it existed only for this URL); TN pagination stops when a page adds no new product (guards every TN site against a server that ignores the page param).
- [x] **T4 — Sporting / VtexPage.** Fetch the API over HTTP (no tab render), log every early stop with its reason, drop the 2500 cap (stop on empty/short page or the `resources` total).
- [x] **T5 — `FullH4rdPage`.** Own page object extracted from `TechStorePage`'s `FULLH4RD` branch: crawls `/productos?page=N` until an empty page, parses `results-card` (title, `price-current`, `price-list`, image, `meta` category), per-page failures retried/logged at WARN instead of silently cutting. `FullH4rdScraper` builds it; `TechStorePage` loses the FULLH4RD enum value and map.
- [x] **T6 — Verify for real.** Boot the backend and scrape the four sites; record counts here.

## Acceptance

- Full backend suite green with `clean` on each commit.
- Real scrape: harvey DONE with >0 and no duplicate-page loop; foreverbstrd > 0; sporting ≈ 7k (before the price band); fullh4rd ≈ 1918 (before the price band).

## Progress / evidence

Runner: `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`.

### T1 — collection loop

New collaborator `ar.scraper.web.SiteResultCollector` (extracted from
`ScraperService.ejecutarScraping`'s collection loop, SRP): the exit condition
is "collected them all", never a fixed loop-index count. `ScraperService`
adds a post-loop pass that registers any still-`EN_CURSO`/`ESPERANDO` site
`ERROR` with `registrarSitioTerminado` — the OLD deadline branch updated
progress/`resultados` but never called `registrarSitioTerminado`, which is
the actual root of "RUNNING forever in `scrape_run_site`".

- Red (`SiteResultCollectorTest.unSitioLentoNoSeAbandona`, old `for (i <
  totalSitios)` port): `Expecting actual: [] to contain exactly: ["harvey"]`
  — a single slow site was abandoned after one per-site timeout, exactly the
  Harvey symptom.
- Green: `SiteResultCollectorTest` 3/3, `ScraperServicePollGranularityTest`
  5/5, `ScraperServiceCancelRetryTest`/`ScraperServiceRetryTest` unaffected.

### T2 — foreverbstrd

Config + doc only (`sitio.foreverbstrd.url` → `/productos/`), no logic to
test-drive. `ScraperFactoryPlatformTest`/`SitioSeedSyncTest` don't pin the
URL string — confirmed green.

### T3 — Harvey outlet + TN repeat guard

`urls_extra` → `/otras-temporadas1/` (plain `?page=N`, mpage removed from
`TiendanubePage.resolveNextPageFromHrefs`/`urlPagina`). New pure guard
`TiendanubePage.todasYaVistas` stops a collection when a page's products are
all duplicates already seen in it (server ignoring the page param).

- Red: compile failure, `todasYaVistas` didn't exist yet (new capability, not
  a bugfix-with-behavior-red).
- Green: `TiendanubePagePaginationTest` 13/13 (adds 5 new tests: 1 proving
  mpage hrefs are no longer recognized, 4 for `todasYaVistas`).
- Behavior change declared: removed `mpageHrefs_returnsNextPage`,
  `mpageSinChocarConPage`, `urlPaginaPreservaMpage` (mpage is no longer a
  recognized pagination param — it only ever existed for Harvey, and Harvey's
  server ignores it entirely); reworked `urlPaginaPaginaUnoDevuelveBase`'s
  fixture off a `?mpage=1` URL onto `?page=1`.

### T4 — Sporting / VtexPage

`scrapeApiLegacy` now fetches via `page.request().get(...)` (no browser tab
render), parses the `resources` response header for the real total, and the
2500 cap is gone (safety ceiling only if the header never shows up: 400
pages × 50 = 20 000). Two new pure helpers: `parseResourcesTotal`,
`continuaPaginando`. IO path: same WARN-on-early-stop treatment, semantics
unchanged.

- Red: compile failure, `parseResourcesTotal`/`continuaPaginando` didn't
  exist yet (new capability).
- Green: `VtexPagePaginationTest` 9/9.

### T5 — FullH4rdPage

New `ar.scraper.pages.FullH4rdPage` (own class, extends `BasePage`):
`/productos?page=N` until an empty page (safety ceiling 300, measured real
end ~p161), one retry per page then WARN (was silent DEBUG). Pure static
`parseListing(html, ...)` parses `article.results-card` off `page.content()`
— same shape as `QloudPage.parseListing`, no browser needed to test. Reuses
`TechStorePage.parsePrecioTech` (shared with Maximus) and `ImageUrl.absolutize`
(shared with Qloud) — no duplication. `FullH4rdScraper` now builds
`FullH4rdPage`; `TechStorePage` loses `FULLH4RD`/`FH_CATS`/`scrapeFullH4rd`/
`extractFH4rd`.

- Red: compile failure, `FullH4rdPage` didn't exist yet (new class).
- Green: `FullH4rdPageTest` 8/8 (dedup by URL, price-current→precio,
  price-list→precioOriginal only if greater, no-price-list abstention, name
  trim, URL/image absolutize, meta→categoria, blank HTML).
- Behavior change declared: deleted `TechStorePageFullH4rdTest.java` (tested
  `parseProductNodes` through the now-removed `TechStoreType.FULLH4RD`) —
  its image-absolutize coverage is superseded by `FullH4rdPageTest`'s own
  root-relative/protocol-relative assertions on the new page.
  `TechStorePage.parseProductNodes`/`fromNode`/`normalizarCat` were left in
  place (untouched, out of scope): they're still referenced by the
  pre-existing (already unreachable from `scrapeAll()`) `extractGenericWithLinks`,
  and the new markup doesn't fit their node shape anyway.

### Parent review (after the writer)

Caveat on the reds above: T1's red ran against a port of the old loop (the
loop was not testable in place), and the T3/T4/T5 reds were compile failures.
Neither is a behavioral red on the shipped code (`CODE-1`).

Fixed in review, each with a behavioral red first:
- `SiteResultCollector` did not count a site whose task threw, so it waited
  for the global deadline (45 min in prod). It also re-looped after an
  interrupt, spinning until the deadline. Red: `unSitioQueExplotaCuentaComoRespondido`
  4.4 s and `unaInterrupcionCorta` 4.0 s (limit 2 s). Green: 0.3 s and 0.002 s.
- `FullH4rdPage` read `&amp;`/`&lt;`/`&nbsp;` literally from the re-serialized
  DOM. Red: `but was: "CABLE HDMI &amp; DP &lt;2M&gt;&nbsp;NEGRO"`. Green:
  `decodesEntitiesFromSerializedDom`.
- `VtexPage`: each `APIResponse` is now `dispose()`d, since Playwright holds
  every body in the driver until then (~8 MB × ~143 pages). No unit test;
  it is covered only by the real scrape (T6).
- Comments trimmed to the non-obvious why (history paragraphs removed).

### T6 — real scrape (standalone harness, no DB, 2026-09-28)

A throwaway `SiteSmoke` main in the scratchpad runs the four scrapers
through `BaseScraper.ejecutar` against the live sites (the user's backend on
:3000 was left alone). Each run exposed a defect the suite could not see:

| Run | foreverbstrd | harvey | sporting | fullh4rd | Found |
|---|---|---|---|---|---|
| 1 | 72 | 108 | 2540 | — | TN guard cut at p2: `scrollToBottom` makes p1's DOM already hold p2..pk, so "everything already seen" is the normal state. Guard → "identical to the previous page" (red `scrollInfinitoNoEsRepetida`). VTEX legacy returns HTTP 400 past `_from` ≈ 2550 → split by category tree (`particionar`, red on a stub). |
| 2 | **190** (130 unique URLs) | **1187** (671 unique) in 442 s | **6382** unique | 623 | A partition cut at `from=1650` on HTTP 500 → 3 retries with backoff. Fullh4rd stopped on an empty page mid-catalog → it only ends past the total the listing declares, explicit `sort=name_asc` (the default order repeats a card every ~60 pages), retry with backoff (red `paginaVaciaAntesDelTotalNoEsFin` on a stub). |
| 3 | — | — | **7134** of 7146 | **1918** (site declares 1914) | Nothing new. |

Not covered by T6: the `ScraperService` post-loop registration of an
abandoned site (T1) only has unit tests; the harness bypasses the service.

### Full suite

`clean test` after T6 fixes: **Tests run: 2960, Failures: 0, Errors: 0, Skipped: 7**
(the skips are pre-existing `PythonRunner*`, no python here). `BUILD SUCCESS`,
no `ERROR]`.
