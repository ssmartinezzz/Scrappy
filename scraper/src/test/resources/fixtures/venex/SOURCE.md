# Venex (osCommerce) fixtures — source and capture notes

- **URLs captured**: `https://www.venex.com.ar/` (homepage nav),
  `https://www.venex.com.ar/componentes-de-pc` (category landing, 604 KB),
  `https://www.venex.com.ar/componentes-de-pc/placas-de-video` (leaf category,
  580 KB) plus `?page=2..30` to find where pagination actually ends.
- **Captured**: 2026-08-13, unauthenticated `curl`, HTTP 200 on all requests.

## Real structure, confirmed live

Each product renders as a `<div class="product-box">` carrying an
`onclick='enhancedClick({"id":...,"name":...,"category":...,"brand":...,
"price":...})'` attribute — clean, well-formed JSON embedded five times per
card (image link, title link, price link, buy button, overlay link), all
identical. This is far more reliable to parse than DOM text scraping, so
`OsCommercePage.parseListing` reads that JSON blob per card rather than the
visible `$ 99.990`-style price text (which is kept only as a fallback/
cross-check candidate, not the primary source). No original/strikethrough
price signal exists anywhere in the listing markup (`old-price`,
`list-price`, `precio-anterior`, `descuento`, `tachado` — all zero
occurrences); `discount: 0` only shows up inside a GA4 analytics
`dataLayer.push(...)` blob, not a real markdown signal. `precioOriginal` is
therefore always `null` for Venex — abstention (`CODE-5`), not a bug.

**Landing vs leaf, confirmed exactly as the design assumed**: both
`/componentes-de-pc` (landing) and `/componentes-de-pc/placas-de-video`
(leaf) show exactly 12 `product-box` items on page 1 — the landing's 12 are
NOT the same items as any leaf page and are not a superset; treating them as
the category's yield would silently drop everything else. The landing DOES
link every leaf sub-category (`/componentes-de-pc/{leaf-slug}`), which is how
`extractLeafCategorySlugs` finds them.

**Pagination — measured, corrects the exploration's "6 pages" estimate.**
`?page=N` on a leaf category returns different product IDs each page (not a
repeat) up to page 14 (12 items each), then page 15 (5 items), and pages 20+
all return the SAME 5 items as page 15 — the real last page. `placas-de-video`
alone is 15 pages / 173 products, not "6 pages" as originally estimated. This
means "stop at the first EMPTY page" alone is insufficient — Venex never
returns an empty page past the end, it repeats the last page forever. The
crawl loop must stop on EITHER condition (design D5 already specified both;
Qloud's `SOURCE.md` learned the hard way that skipping the repeat check is
not safe to assume elsewhere either): a page contributing zero NEW urls,
whether because the page is empty or because every url on it was already
seen.

## Fixture construction

- `leaf-page1.html` / `leaf-page2.html`: 12 and 6 cards respectively — real
  product ids/names/prices/brands from the captured `placas-de-video` page
  (ids 18549, 18029, 18167, 18203, 20122, 20125, 20126, 20407, 20960, 22171,
  17946, 18710 on page 1; a further 6 real ids from later pages), reformatted
  into a compact card template that preserves every field the parser reads
  (`product-box` wrapper, `enhancedClick({...})` JSON, `<img src>`) without
  the ~580 KB of unrelated page chrome (`PR-2`: goldens should not inflate a
  diff without inflating coverage).
- `leaf-page3-empty.html`: synthetic "no results" page — real empty-result
  pages beyond the true last page instead REPEAT the last page's content
  (see pagination note above), so this fixture exercises the empty-page half
  of the stop condition specifically; the repeat-detection half is exercised
  by feeding `leaf-page1.html` twice in `OsCommercePageListingTest`.
- `landing-componentes-de-pc.html`: reuses the 12-card page1 body (a real
  landing page also shows exactly 12) plus the real leaf sub-category links
  captured from `/componentes-de-pc`.
- `nav.html`: real top-level category links captured from the homepage nav,
  plus `carrito`/`mi-cuenta` (denylisted, non-listing pages that legitimately
  appear in any e-commerce nav).
