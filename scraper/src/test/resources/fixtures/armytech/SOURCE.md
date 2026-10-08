# Armytech fixture

`listing.json` is the PrestaShop 1.7 listing JSON, captured 2026-10-08:

    curl -s -A "Mozilla/5.0" -H "Accept: application/json" \
         -H "X-Requested-With: XMLHttpRequest" \
         "https://www.armytech.com.ar/419-procesadores"

- The 21 `procesadores` products are real and untouched (one has `cover: false`, no discount).
- The `rendered_*`, `sort_orders` and `js_enabled` keys were stripped for size.
- Spliced in from `/2-productos?page=1` (same shape, same capture day): one product with
  `has_discount: true` (Mousepad Fantech, regular 7552.578 vs final 5038.89) and the two
  `price_amount: 0` brand placeholders ("Marca - Intel", "Marca - AMD").
- `pagination` is the category's own (`pages_count: 1`, `total_items: 21`), not recomputed.
- A missing cover is `false`, not `null`.
