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

## Open question resolved: `imagenes.compragamer.com` path prefix — UNRESOLVED

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
