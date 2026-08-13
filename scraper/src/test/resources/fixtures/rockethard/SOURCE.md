# Rockethard (Qloud) fixtures — source and capture notes

- **URLs captured**: `https://rockethard.com.ar/hardware/` (page 1, 524 KB,
  96 `data-precio` card blocks = 48 unique products — every product renders
  TWICE in the DOM, a desktop card and a mobile-layout duplicate, same URL
  both times), `https://rockethard.com.ar/` (homepage nav, for category
  discovery), `https://rockethard.com.ar/productos` (confirmed: 301 ->
  `/productos/` -> **404**).
- **Captured**: 2026-08-13, unauthenticated `curl`, HTTP 200 (nav/hardware),
  404 confirmed for `/productos`.
- **Pagination discovered live, not in the original design**: `/hardware/`
  alone has 174 products across 4 pages (`?page=N`, `N=1..4`; page 5 returns
  0 cards). Design D5 only spelled out `?page=N` pagination for osCommerce/
  Venex — Qloud/Rockethard needs the same stop-at-first-empty-page loop, or
  a category with more than one page silently undercounts by up to ~75%
  (174 real vs 48 if only page 1 were read). `QloudPage`'s crawl loop
  applies it too; not covered by `parseListing`'s unit fixture (single-page
  parsing correctness), covered by the live run (task 4.5).
- **`hardware.html`**: trimmed to 5 `<!--Card-->` blocks (4 unique products +
  1 exact desktop/mobile duplicate of the first, verbatim card markup from
  the real page) plus one synthetic "no markdown" card (`memoria-4gb...`,
  `tachado` equals the final price — real captured pages didn't happen to
  contain a no-discount example in the pages sampled) and one synthetic
  below-price-band item (`cable-hdmi-barato`, price $50) to exercise the
  price filter.
- **`nav.html`**: synthetic, built from the real homepage's distinct
  single-flat-segment `https://rockethard.com.ar/<slug>/` links (captured
  2026-08-13) plus one deliberately added `/productos/` link — the real nav
  does NOT link there, this fixture proves the parser would still exclude it
  even if it did.
- **Category discovery, live**: the real homepage nav resolves to more
  categories than design's frozen 8-slug fallback list — `arma-tu-pc`,
  `atencion-a-empresas`, `butacas`, `contacto`, `elegi-tu-pc`,
  `eligi-tu-combo`, `escritorio-mesa-gamer-`, `hogar`, `impresora`,
  `portatil`, `smart-tv` also exist. `butacas` (gaming chairs, 12 unique
  products, spot-checked live) matters: it's the concrete example the spec's
  own "textile category defeats rubro_forzado" scenario names. The denylist
  excludes non-listing pages only (`contacto`, `atencion-a-empresas`,
  `arma-tu-pc`, `elegi-tu-pc`, `eligi-tu-combo`) — everything else discovered
  from the nav is crawled.
