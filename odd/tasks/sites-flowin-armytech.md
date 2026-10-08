# Sites: Flowin and Armytech

Branch: `feat/sites-flowin-armytech` (also carries the docs fix in commit 3e1abdd).

## Objective

Scrape two new stores:

- **Flowin** (`https://flowin.com.ar/`) — Shopify, office furniture (standing desks, ergonomic
  chairs). `/products.json` serves 8 products, all on page 1. Rubro `oficina`.
- **Armytech** (`https://www.armytech.com.ar/`) — PrestaShop 1.7, PC hardware. New platform
  value `prestashop`, new page.

## Evidence (measured 2026-10-08, curl)

- Flowin: `products.json?limit=250&page=1` → 8 products, page 2 → 0. Prices 590.000–1.999.999.
- Armytech: `GET /2-productos?page=N` with `Accept: application/json` +
  `X-Requested-With: XMLHttpRequest` returns PrestaShop's listing JSON: `products[]` (`name`,
  `url`, `price_amount`, `regular_price_amount`, `has_discount`, `cover.large.url`,
  `category_name`, `id_product`) and `pagination` (`total_items` 549, 48 per page,
  `pages_count` 12). Category `2-productos` is the root. Rows like "Marca - Intel" have price 0
  (brand placeholders) — the price band drops them.
- `robots.txt` disallows `?order=`, `?n=`, `?search_query=`… but not `?page=`.

## Constraints

- CLAUDE.md rules: new platform = migration CHECK + `PLATAFORMAS_VALIDAS` + `ScraperFactory.PAGINAS`
  + `PlatformVocabularySyncTest` pointer + `SitioSeedSyncTest` list (docs/ADD_SCRAPER.md Caso 5).
- Next migration: `V43`. Its CHECK must re-list the FULL current domain.
- Empty discovery/listing is an exception, not a result (ADD_SCRAPER Caso 2b rule 3).
- TDD: strict (session config). Runner:
  `JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`

## Tasks

- [x] S1 Flowin: config + seed row (`shopify`, rubro `oficina`) in V43; `SitioSeedSyncTest` list.
- [x] S2 Armytech: `prestashop` platform (V43 CHECK + seed, `PLATAFORMAS_VALIDAS`,
      `PlatformVocabularySyncTest`, `ScraperFactory` + `ScraperFactoryPlatformTest`).
- [x] S3 `PrestashopPage`: listing-JSON parser as a pure static helper tested on a fixture
      (RED first), browser edge only fetches pages; config `sitio.armytech.*` rubro `tecnologia`.
- [x] S4 Full suite green with `clean`.
- [x] S5 Real run of both sites: product count and image count per site in the log.
- [x] S7 Boot order: `SiteRegistry` must load after Flyway (`@DependsOnDatabaseInitialization` on `JdbcSiteSource`; proven on a real first run).
- [x] S6 Docs: `docs/SITES.md`, `docs/ADD_SCRAPER.md` (PrestaShop case).

## Acceptance

- Flowin yields 8 products with images; Armytech yields ≈ the `total_items` minus price-0 rows.
- No new name-sets; routing only through `sitio.plataforma`.

## Progress

S1-S4 done 2026-10-08 (uncommitted).
- RED: `PrestashopPageListingTest` against a stub parser: 8 run, 3 failures + 3 errors (price-0 rows kept, pages_count 0, no exception on bad payload, products not found). GREEN after implementing: 8/8.
- Fixture: `scraper/src/test/resources/fixtures/armytech/listing.json` (real capture, see SOURCE.md); real `cover` absent is `false`, not null; `precio.minimo=0` so price-0 rows are dropped by an explicit `precio <= 0`.
- V43 re-lists the 13-value domain + `prestashop`; seeds Flowin (shopify, oficina) and Armytech (prestashop, tecnologia). ShopifyPage uses `domain(baseUrl)` + `/products.json`, so `https://flowin.com.ar/` works.
- Rollback block `V43` added to docs/DATABASE.md; V24/V27/V28 rollback tests run it first (closed-domain n -> n+1 in V24, plus the extra `sqlFor("V43")` call).
- GANAN_A_LA_URL includes `prestashop` (custom page, must not yield to URL fallbacks).
- Full suite (`clean test`): Tests run: 3576, Failures: 0, Errors: 0, Skipped: 7, BUILD SUCCESS.

S5 done 2026-10-08 (real run, dev DB, V43 applied by Flyway on boot).
- Flowin: 8 products, images 8/8 (1.3s). Armytech: 546 products (549 total_items minus 3 price-0 placeholders), images 529/546 (cover absent on 17), 37.6s for 12 pages. Both concurrent in one run: OK.
- DB (active): Flowin 8 (oficina, 8 with image); Armytech 546 (543 tecnologia + 3 indumentaria: 2x "Guantes Simulador Thermaltake" -> Guantes, "Elgato Cold Shoe Mount" -> Zapatilla, all classifier misfires), 23 in "Otros" (powerbanks, speakers, "Articulo De Prueba").
- Flowin categories: Silla/Escritorio; the two bundles ("Escritorio + Silla", "Pack Flowin Lift + Silla") classified Silla.
- Anomaly, DIAGNOSED (not a flake): the first run after the boot that applied V43 returned 0 for both
  sites with no error. `SiteRegistry` loads `sitio` once, in its constructor; that boot built it
  before Flyway applied V43 (`flyway_schema_history.installed_on` 09:27:06.99, after the catalog
  restore at 09:27:04-06). Both keys missed the cache, `porKey` abstained to `tiendanube`, and
  TiendanubePage found nothing on a Shopify and a PrestaShop store. PrestashopPage cannot return an
  empty list without throwing, which proves it was never routed. The restart read the seeded rows.
  Impact: on any deploy, the first run after a new-site migration yields 0 for the new sites.

S7 proof 2026-10-08 (real boot, dev DB returned to pre-V43 first). Rolled back V43 per docs/DATABASE.md plus `DELETE FROM flyway_schema_history WHERE version='43'`; to unblock the FK I deleted Flowin/Armytech data: 6 `scrape_run_site` rows, 554 `productos` (8 + 546; cascaded 554 `precio_historico`), then the 2 `sitio` rows. Jar's `JdbcSiteSource` carries `@DependsOnDatabaseInitialization` (javap). Boot (java 21, crons 3/4 disabled): V43 `installed_on` 09:55:14.39 (Flyway), `Started App` 09:55:16. FIRST run after that boot, no restart: `[SITIO] Flowin -> 8 productos (1.3s) fotos 8/8`, `[SITIO] Armytech -> 546 productos (38.9s) fotos 529/546`; status DONE, extractionStats 8/8 and 546/546. Before the fix the same first run gave 0 for both. Backend stopped, crons 3/4 re-enabled, `last_run_at` unchanged (2026-10-05 17:42:21).

## Next step

None. Merged as #295 (9660e68, 2026-10-08). Open follow-ups, not in scope: 3 Armytech rows misclassified into indumentaria, 23 in Otros, Flowin bundles as Silla; ProductRepository also reads the catalog before Flyway on boot (same class as S7).
