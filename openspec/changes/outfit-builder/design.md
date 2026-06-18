# Design: Armador de Outfits (outfit-builder + outfit-feedback)

## Technical Approach

Two capabilities, no new data source. Outfit assembly reads the **in-memory aggregated catalog** (`service.getLastResult().productos()`) — the same source every existing read endpoint uses (`/api/data`, `/api/mejores`, `/api/marcas-browser`), NOT a fresh DB query. `OutfitService` holds a static `categoria → slot` map and a price-band-weighted sampler with a 3-step fallback. Two new endpoints in `ApiController` (`GET /api/outfits`, `POST /api/outfits/feedback`) follow the existing controller idioms (`ObjectNode`/`ArrayNode`, `noContent()` when no data). Feedback is collection-only: a new additive `outfit_feedback` table mirroring the `favoritos`/`precio_historico` style in `DatabaseService`. Frontend adds one `/outfits` route (matching the `FavoritosPanelRoute` wiring) whose panel renders an internal sub-tab strip; only the Gym tab has logic, others show "Próximamente". Reuses `FavoritosPanel.jsx` card/interaction precedent.

## Architecture Decisions

### ADR-1: Footwear slot bypasses `gymrat`, uses a `categoria` whitelist

| Option | Tradeoff | Decision |
|--------|----------|----------|
| Make `esGymrat()` footwear-aware | Breaks GYMRAT badge semantics (`esCalzado()` guard at NormalizerService L530 is load-bearing for the existing Modo GYM facet) | Rejected |
| Separate `Zapatilla*` whitelist for the calzado slot only | Footwear never carries `gymrat==true` by design, but the outfit needs shoes | **Chosen** |

**Rationale**: `esGymrat()` returns `false` for any calzado (hard guard). Torso/piernas slots filter `gymrat==true`; the calzado slot filters by a footwear `categoria` whitelist (`Zapatilla*` plus `Sneaker`) independent of the flag. `esCalzado()` is NOT touched.

### ADR-2: Learning is collection-only this change

| Option | Tradeoff | Decision |
|--------|----------|----------|
| Wire feedback counters into sampling now | Zero training signal on day one — would be ML theater | Rejected |
| Persist like/dislike rows, do not read them back | Honest first step; future fast-follow can weight sampling | **Chosen** |

**Rationale**: No labeled outfit-affinity data exists. Sampling stays a deterministic price-band heuristic; `outfit_feedback` only accumulates signal. Counter-based weighting is a documented fast-follow, out of scope.

### ADR-3: Read from in-memory aggregate, not the DB (deviation from explore.md)

**Choice**: `OutfitService` consumes `service.getLastResult().productos()`.
**Alternatives**: per-slot SQL in `DatabaseService` (as explore.md suggested).
**Rationale**: Every existing read endpoint filters the in-memory list; the DB holds the persisted catalog but live serving uses the aggregate. Following the DB path would invent an inconsistent pattern. `DatabaseService` is touched ONLY for `outfit_feedback`.

## Data Flow

```
GET /api/outfits?genero=hombre
  ApiController.outfits()
    └─ service.getLastResult().productos()  (in-memory)
         └─ OutfitService.armar(productos, genero)
              1. partition by slot via categoria→slot map
              2. per slot: filter (gymrat||whitelist) + genero-eligible + price band
              3. weighted pick one per required slot (torso,piernas,calzado)
              4. fallback if any slot empty (see sequence)
         ◄─ Outfit{slots[], genero, partial}
    └─ serialize ObjectNode  ──►  OutfitsPanel.jsx

POST /api/outfits/feedback {slotUrls[],genero,liked}
  ApiController.outfitFeedback() ─► db.guardarOutfitFeedback(...) ─► outfit_feedback
```

### Sequence: slot sampling + fallback

```
armar(productos, genero):
  band = priceBand(median of eligible)        # ±X% window
  for slot in [torso, piernas, calzado]:
     cands = pool(slot, genero, band)
     if cands empty: relax price band  -> cands = pool(slot, genero, ALL)
     if cands empty: relax genero=unisex -> cands = pool(slot, "unisex", ALL)
     if cands empty: partial=true; skip slot
     else pick = weightedRandom(cands)
  accesorio = optional, best-effort (no fallback)
  return Outfit(picks, genero, partial)
```

`genero` policy: a gendered request matches `genero==requested OR unisex OR ""` (empty treated unisex-eligible). Re-roll = the same GET re-invoked (each call re-samples).

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `scraper/.../web/OutfitService.java` | Create | `@Service`: `categoria→slot` map, slot pools, price-band weighted sampler, 3-step fallback. Pure functions over `List<Product>` |
| `scraper/.../web/ApiController.java` | Modify | Inject `OutfitService`; add `GET /api/outfits`, `POST /api/outfits/feedback`. Reuse `safe()`, image `//`-prefix fix |
| `scraper/.../db/DatabaseService.java` | Modify | `outfit_feedback` DDL in `crearTablas()`; `guardarOutfitFeedback(...)` mirroring `guardarFavorito` |
| `frontend/src/components/OutfitsPanel.jsx` | Create | Sub-tab strip (Gym\|Casual\|Formal), `OutfitCard` sub-component (3-4 slots, re-roll, 👍/👎), placeholders |
| `frontend/src/components/AppLayout.jsx` | Modify | `lazy()` import + `OutfitsRoute` wrapper + export + NavLink tab |
| `frontend/src/App.jsx` | Modify | `<Route path="outfits" element={<OutfitsPanelRoute/>}/>` |
| `frontend/src/api.js` | Modify | `fetchOutfit(genero)`, `sendOutfitFeedback(body)` |

## Interfaces / Contracts

> Spec runs in parallel; shapes below are the design's proposed contract. If `sdd-spec` finalizes a different shape, reconcile field names there — these are the load-bearing fields.

```
// GET /api/outfits?genero=hombre  → 200 (or 204 if no catalog loaded)
{ "genero":"hombre", "partial":false,
  "slots":[ {"slot":"torso","sitio":"","nombre":"","precio":0,"url":"","img":"",
             "categoria":"","marca":""}, ... ] }    // slot ∈ torso|piernas|calzado|accesorio

// POST /api/outfits/feedback  body:
{ "genero":"hombre", "liked":true,
  "slots":[ {"slot":"torso","url":"..."}, ... ] }    // → {"ok":true}
```

```sql
CREATE TABLE IF NOT EXISTS outfit_feedback (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    genero      TEXT,
    liked       INTEGER NOT NULL DEFAULT 0,
    slots_json  TEXT NOT NULL,          -- JSON array of {slot,url}
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outfit_fb_liked ON outfit_feedback(liked);
```

`categoria → slot` map (from explore taxonomy): torso = Puffer, Campera, Sweater, Buzo, Musculosa, Camisa, Remera, Chomba, Casaca, Chaleco, Saco, Traje, Piloto; piernas = Calza, Baggy, Jean, Jogging, Short, Bermuda, Pollera, Pantalón; calzado = Zapatilla* (prefix), Sneaker; accesorio = Mochila, Bolso, Riñonera, Billetera, Cinturón, Bufanda, Guantes, Gorro, Gorra, Lentes, Medias.

## Testing Strategy

| Layer | What to Test | Approach |
|-------|--------------|----------|
| Unit | slot mapping, fallback order, genero eligibility, weighting | No test infra (config.yaml `tdd:false`) — manual via curl + sample catalog |
| Integration | `GET /api/outfits` returns 3 slots; sparse genero degrades not crashes; feedback row persists | Manual: run scraper, hit endpoints, inspect `scraper.db` |
| E2E | Outfits tab renders, re-roll changes combo, 👍/👎 persists, non-gym shows "Próximamente" | Manual at localhost:3000 (no console errors) |

## Migration / Rollout

`outfit_feedback` is additive (`CREATE TABLE IF NOT EXISTS`) — no migration. No feature flag. Rollback per proposal: delete the two new files, revert the controller/db/frontend additions; table can stay harmlessly. Rebuild: `cd frontend && npm run build` then `mvn -f scraper/pom.xml clean package -DskipTests`.

## Open Questions

- [ ] Response field names (`slots` vs `items`, `img` vs `imagenUrl`) must match whatever `sdd-spec` defines — design uses `/api/data`-style names (`img`, `precio`). Reconcile at spec finalization.
- [ ] Price-band width (±%) and weighting function (e.g. inverse-distance-to-median vs ML `scoreP`) left to implementation; spec should not over-constrain.
- [ ] Re-roll is stateless (re-GET). If spec wants exclusion of the previous combo, that needs a request param — flag if so.
