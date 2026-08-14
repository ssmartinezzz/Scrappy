# Maximus fixtures — source and capture notes

- **URLs captured**: `https://www.maximus.com.ar/` (homepage, category nav),
  and a real `POST https://www.maximus.com.ar/wfmWebSite2.aspx/wsNRW_Script`
  for `cat_id=48` (Placas de Video) — first WITHOUT session cookies (confirms
  the documented gate shape), then WITH cookies minted by a prior `GET` of a
  category page (confirms the real success shape).
- **Captured**: 2026-08-13, unauthenticated `curl` for the gate case,
  cookie-carrying `curl` for the success case.

## URL scheme, confirmed live

Category links on the homepage:
```
/Productos/Placas-De-Video/maximus.aspx?/CAT=48/SCAT=-1/M=-1/OR=3/PAGE=1/
/Productos/Notebooks/maximus.aspx?/CAT=56/SCAT=-1/M=-1/OR=1/PAGE=1/
/Productos/Computadoras/maximus.aspx?/CAT=68/SCAT=-1/M=-1/OR=1/PAGE=1/
/Productos/Monitores/maximus.aspx?/CAT=59/SCAT=-1/M=-1/OR=1/PAGE=1/
/Productos/gabinetes/maximus.aspx?/CAT=1/SCAT=-1/M=-1/OR=1/PAGE=1/
```
Only 5 categories are linked from the homepage — the design's frozen fallback
list (`{48,49,...,60}`) is a superset guessed from prior exploration, kept as
the named fallback per design D5.

**The `{Slug}` path segment is cosmetic** — confirmed live:
`GET /Productos/cualquier-cosa/maximus.aspx?/CAT=48/.../PAGE=1/` returns 200,
identical to the real slug. Only `CAT=` routes. `QloudLiveRun`/`OsCommerce`
both needed real discovered slugs for their listing pages to render at all;
Maximus does not — the slug only affects the URL's cosmetics/SEO, not the
in-page API call that actually returns products.

## Session gate — confirmed live, exact shape

A cookie-less `POST` to `/wfmWebSite2.aspx/wsNRW_Script` returns HTTP **200**
(not an error status — the gate can't be detected from the status code) with
body:
```json
{"d":"-2, Módulo GlobalBluePoint© GBPScripts NO ADQUIRIDO."}
```
`session-gate.json` holds the exact `d` VALUE (not the outer envelope) —
`parseMaximusPayload(String d)` receives the string already extracted from
`{"d": ...}` by the caller, so the fixture is what that function actually
sees: a plain-text, non-JSON string.

## Success shape — confirmed live, WITH session cookies

After a `GET` to a category page (which mints
`ASP.NET_GBP_SessionId_*`/`GBP_<guid>` cookies) the SAME `POST` with those
cookies attached returns the real payload. `d`'s value (once JSON-decoded
from the outer envelope) is itself a JSON object:
```json
{"scName":"web.MAX.GetItemList4Search_v3_V6","data":{"page":1,"pagesTotal":3,"itemsTotal":60,"items":[...]}}
```
Real capture for `cat_id=48`: `itemsTotal=60, pagesTotal=3`, 28 items on page
1. Fixtures use round numbers (20/20/19 = 59 across 3 pages) to match the
task's stated `itemsTotal=59, pagesTotal=3` exactly, built from the real
item field shape (trimmed to the fields the parser reads):
`item_id, item_code4web, item_desc, item_desc4link, prli_price,
prli_price_original, strikeThroughPrice, strikeThroughPrice_original,
item_outlet`.

**No image field exists anywhere in the API response** (checked every key on
a real item — confirmed absent). `imagenUrl` is therefore always `""` for
Maximus products (abstention, `CODE-5` — no signal, nothing invented).

**`strikeThroughPrice_original` is not always a real markdown**: one real
item had `strikeThroughPrice_original` numerically EQUAL to
`prli_price_original` (no actual discount, field just always populated).
`precioOriginal` only gets that value when it is strictly greater than
`precio` — same "real discount, not a copy" rule used for Compragamer/
Rockethard.

Product URL, confirmed live: `/Producto/{item_desc4link}/ITEM={item_id}/maximus.aspx`
returns 200.
