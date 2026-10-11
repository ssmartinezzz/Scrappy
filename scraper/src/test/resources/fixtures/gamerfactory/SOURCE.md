# Gamer Factory fixture

`search.json` is the public search API of the storefront, captured 2026-10-10:

    curl -s -A "Mozilla/5.0" \
      "https://core.gamerfactory.com.ar/products/search?filter=%7B%7D&page=1&perPage=1000&sort=%7B%7D"

- 8 of the 828 real products, untouched except that `description`, `seo` and the
  `installmentsOptions*` keys were stripped for size (the parser reads none of them).
- Picked to cover: no discount (cpu, ram, simulator, build), a real discount
  (`strikethrough > ARS`: gpu, mouse, chair), several images vs. one, and the `other`
  type ("Prueba", a test row the API really serves).
- `total` is the API's real 828, NOT recomputed, so it is larger than `data.length`.
- No product in the capture lacks `images`; the missing-image case is built inline in the test.
