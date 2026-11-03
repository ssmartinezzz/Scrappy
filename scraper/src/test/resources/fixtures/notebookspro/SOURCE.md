# notebookspro (WooCommerce, Flatsome theme) — source

- **URL captured**: `https://notebookspro.ar/tienda/` (page 1), unauthenticated `curl`, HTTP 200, 2026-11-03.
- **`tienda.html`**: the first two product cards, verbatim, inside a minimal wrapper.
  Flatsome renders each card as `div.product-small.col.type-product` (no `li.product`,
  no `ul.products`); the image is lazy (`src` is an SVG `data:` URI, the real URL is in `data-src`);
  sale prices are `<del>` (list) + `<ins>` (current), formatted `$800.000`.
- The store paginates `/tienda/page/N/`, 8 cards per page; `x-wp-total: 133` on the Store API.
