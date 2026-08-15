# Compragamer fixtures — source and capture notes

- **URL captured**: `GET https://static.compragamer.com/productos` (1404 items,
  ~9.4 MB), `GET https://static.compragamer.com/categorias_sub` (110 items),
  `GET https://static.compragamer.com/marcas` (213 items).
- **Captured**: 2026-08-13, unauthenticated `curl`, HTTP 200 on all three.
- **Trimming**: `productos.json` keeps 10 items, most copied verbatim from the
  real payload (`id_producto` 1674, 3903, 2113, 14895) with only the fields
  `TechStorePage.parseCompraGamerFeed` reads kept. Items `20001`-`20006` are
  synthetic — same field shape as the real payload, values chosen to isolate
  one filter/mapping rule each (see the test class for which case exercises
  which item). `categorias_sub.json`/`marcas.json` keep only the ids the
  fixture products reference.

## `imagenes.compragamer.com` path prefix — RESOLVED 2026-08-15

The prefix is:

```
https://imagenes.compragamer.com/productos/compragamer_Imganen_general_<nombre>.jpg
```

`Imganen` is their typo, not one here, and it is load-bearing. `-grn.jpg`
instead of `.jpg` serves the smaller variant the store's own grid uses; both
return 200.

**How it was resolved**: the headless-browser capture the note below called for.
Driving a real Chrome at `https://compragamer.com/productos?cate=58` and reading
the rendered `<img>` elements after the product grid hydrated gave the literal
URLs; matching one back to its feed entry (`id_producto` 20213) showed the feed's
`imagenes[].nombre` is exactly the middle of that path. HEAD on the URL rebuilt
from the fixture's own `nombre` values returns 200 `image/jpeg`.

**Why the earlier probes all failed**: they were right that `403 AccessDenied`
is not proof of a wrong prefix, and right not to guess further — but the guessed
set never included the `compragamer_Imganen_general_` infix, which no amount of
path-shape guessing would have produced. The grep of the shipped bundles failed
because the URL is not built from a literal template in the JS at all.

Also resolved in the same pass: the product route. `/producto/{id}` — what the
first implementation built — is not a route; the SPA router keys on the trailing
`_{id}` of the last segment, so it bounced every product link to the homepage.
`/producto/{slug}_{id}` is the real form and the slug is cosmetic
(`/producto/x_20213` renders the right product).

## Historical note: how this was left open

_Kept verbatim — the reasoning was sound and the follow-up it named is exactly
what closed the question._

## Open question: `imagenes.compragamer.com` path prefix — UNRESOLVED (at capture time)

Design (`sdd/fix-zero-yield-tech-sites/design`, D3) left the exact CDN path
prefix as an open question to confirm against one live URL. Attempted here:

- The bucket at `imagenes.compragamer.com` is reachable and serves real assets
  (e.g. a home-banner image referenced from the SPA's `main-*.js` returns 200
  with a valid `image/webp` body).
- Every guessed path for a real product image `nombre` from the captured
  payload (bare name, with `.jpg`/`.webp` extension, under `productos/`,
  `products/`, `img_productos/`, `thumb/`, `full/`, `medium/`, keyed by
  `id_producto`/`id_producto_imagen` instead of `nombre`) returned `403
  AccessDenied` — consistent with an S3-style bucket that returns
  `AccessDenied` rather than `404` for a nonexistent key, not proof the whole
  prefix is wrong.
- Grepping the SPA's shipped JS bundles (`main-*.js` + all `chunk-*.js` linked
  from `index.html`) for the function that builds a product-card image URL
  found no literal template — the component that renders `imagenes[]` into a
  URL is very likely behind a route-level lazy chunk that a `curl` of `/`
  never loads, and no headless-browser network capture was available in this
  sandbox (`google-chrome --headless=new --net-log=...` under `xvfb-run`
  started the process but did not persist events before the run was cut off
  by `--virtual-time-budget`).

**Decision**: implement exactly what the design specified —
`https://imagenes.compragamer.com/<nombre>` (no extension, no extra path
segment) — because that is the literal, already-reviewed contract, and no
counter-evidence was found (every probe was inconclusive `403`, not a
confirmed wrong answer). This is a known follow-up: if the image field turns
out blank/broken for Compragamer products after a live run, capturing real
browser network traffic against `/productos` is the next step, not more
guessing.

_Outcome: the field was broken for 100% of rows, and the browser capture named
here is what resolved it. See the section at the top of this file._
