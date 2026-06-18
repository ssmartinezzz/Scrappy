# Exploration: outfit-builder

**Date:** 2026-06-17
**Change:** outfit-builder
**Status:** done

---

## Goal

New "Armador de Outfits" tab in the dashboard. First slice: GYM outfits — assemble complete outfits (top + bottom + footwear, optionally accessory) from products already tagged as gym gear, split by `genero` (hombre/mujer/unisex). Other outfit categories (casual, formal, etc.) get placeholder tabs only — no logic. The system should ideally "learn" over time which outfit combinations are good, similar in spirit to the existing ML pipeline.

## Correction to stale docs

`openspec/config.yaml` (line 9) and `.atl/skill-registry.md` describe the frontend as "vanilla HTML/CSS/JS". This is **stale**. The real frontend is **React 18 + Vite 5** in `frontend/src/` (components under `frontend/src/components/*.jsx`, routing via `react-router-dom` v6 in `frontend/src/App.jsx`), confirmed directly in code and in `CLAUDE.md`. Build output goes to `scraper/src/main/resources/static/`. All design/tasks work must target `frontend/src/`, never `resources/static/`.

---

## Current State

### `Product` record (`scraper/src/main/java/ar/scraper/model/Product.java`)

```java
record Product(
    String sitio, String nombre, double precio, String precioOriginal,
    String url, String imagenUrl, String categoria, String genero,
    List<String> talles, MlScore ml, String marca,
    String rubro,      // "indumentaria" | "tecnologia" | "suplementos"
    boolean gymrat      // tag transversal aditivo — NO altera categoria/rubro
)
```

`categoria` is a **flat string** — there is no "slot" dimension (top/bottom/footwear) today. It has to be derived by mapping known `categoria` values into slots.

### `gymrat` flag (`NormalizerService.esGymrat()`, lines 521–543)

- OR rules: keyword match in `KW_TRAINING_ROPA` (training, gym, workout, crossfit, pesas, musculosa gym, etc.), OR site in `GYM_SITIOS`, OR site contains "entreno".
- **Hard guard: `if (esCalzado(cat)) return false;`** — gymrat is explicitly apparel-only, footwear is *structurally excluded* by design (see `esCalzado()`, line 546: `Zapatilla*`, `Botines`, `Borcego`, `Botas`, `Ojotas`, `Sneaker`).
- Also excluded: `suplementos` rubro and `Suplemento`/`Alimentos` categoria.

**Implication:** a gym outfit's footwear slot can never come from `gymrat == true` products. Footwear must be matched separately via a `categoria` whitelist (e.g. `Zapatilla Entrenamiento`, `Zapatilla Running`, or any `Zapatilla*`), independent of the `gymrat` flag.

### Known `categoria` taxonomy (`esIndumentariaOCalzado()`, lines 554–567)

| Slot (proposed) | `categoria` values observed |
|---|---|
| Torso | Puffer, Campera, Sweater, Buzo, Musculosa, Camisa, Remera, Chomba, Casaca, Chaleco, Saco, Traje, Piloto |
| Piernas | Calza, Baggy, Jean, Jogging, Short, Bermuda, Pollera, Pantalón |
| Calzado | Zapatilla* (incl. "Zapatilla Entrenamiento"/"Running"), Botines, Borcego, Botas, Ojotas, Sneaker |
| Cuerpo entero | Vestido, Enterito |
| Accesorio | Mochila, Bolso, Riñonera, Billetera, Cinturón, Bufanda, Guantes, Gorro, Gorra, Lentes, Medias |
| Interior | Calzoncillos, Corpino, Malla |

This mapping is not encoded anywhere today — it would be new logic (a `categoria → slot` lookup table).

`genero` is a free string (`hombre`/`mujer`/`unisex`/`""`) — empty values are common in the wild, so outfit assembly needs an explicit policy for products with no `genero` (e.g. treat empty as `unisex`-eligible, or exclude from gendered outfits and only use for a "unisex" outfit set).

### API surface (`ApiController.java`)

Existing endpoints (`/api/data`, `/api/facets`, `/api/tendencias`, favorites-related) already support filtering by `categoria`, `genero`, `gymrat`. No `/api/outfits` endpoint exists. An outfit endpoint can reuse the same query/filter plumbing `ApiController` already has for `/api/data`, just calling it N times (once per slot) or adding a dedicated query method in `DatabaseService`.

### Persistence (`DatabaseService.java`)

SQLite, additive-migration style (`CREATE TABLE IF NOT EXISTS`). No outfit-related table exists. A new `outfit_feedback` (or similar) table would follow the same pattern as `precio_historico`/`ml_output`.

### ML pipeline (`ml_pipeline.py`, `MlEnricher.java`, `PythonRunner.java`)

Learns from the *whole catalog* each scrape run (price percentiles, TF-IDF clustering) — it has no concept of explicit user feedback today, and there is **no labeled outfit-affinity data anywhere** (no table, no signal). "Learning" for outfits would need a new data source before it can mean anything beyond a static heuristic.

### Frontend routing (`App.jsx`, `AppLayout.jsx`)

Flat `react-router-dom` v6 routes, each top-level tab is its own route component (see `Sidebar.jsx` for the nav list). Existing precedent: `FavoritosPanel.jsx` already implements user-driven implicit-feedback-like behavior (mark/unmark favorite) against the same `Product` shape — useful reference for any "like this outfit" interaction.

---

## Outfit assembly approaches

### A — Static slot lookup + price-band-weighted sampling (recommended)

Hardcode `categoria → slot` map (torso/piernas/calzado/accesorio per table above). For a given `genero`, pick one random-but-weighted product per required slot (torso, piernas, calzado; accesorio optional) from `gymrat == true` products for torso/piernas and a footwear-categoria whitelist for calzado, restricted to a price band so the outfit looks coherent. Re-roll endpoint regenerates a new combo on demand.

- **Pros:** no new ML/training step, ships fast, deterministic and explainable, reuses existing `categoria`/`genero`/`gymrat` fields as-is.
- **Cons:** slot map is hand-maintained; needs fallback logic when a slot has zero matches for a given `genero`+price-band (common risk — gym footwear tagged "unisex" or missing `genero` entirely).

### B — Weighted random sampling across full price range, no price-band constraint

Same as A but without bucketing by price — just sample randomly per slot. Simpler, but outfits can look mismatched (e.g. $300k zapatillas with a $8k musculosa).

### C — ML-scored combos (cluster/score outfit coherence via `ml_pipeline.py`)

Extend the Python pipeline to score torso/piernas/calzado combos (e.g. by brand co-occurrence or TF-IDF similarity of names) and rank candidate outfits instead of random sampling.

- **Pros:** more "intelligent" pairing.
- **Cons:** there's no real signal to train this on yet (no purchase/outfit data) — would just be unsupervised heuristics dressed up as ML, adds subprocess complexity for marginal benefit at this stage.

**Recommendation: A.** It's the only approach that ships something real without inventing data that doesn't exist, and the price-band constraint is the cheapest way to get visually coherent results.

---

## "Learning" mechanism approaches
