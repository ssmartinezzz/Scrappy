# Design: Outfit Recommendation Quality

## Technical Approach

Three independent fronts converge on one goal: make the Gym outfit builder produce
correct, learning-aware recommendations. None of them adds a data source or schema.

1. **Feedback read-path** — `OutfitService.armar()` becomes feedback-aware. On each
   invocation it consumes a pre-built feedback model (an exclude-set + boost-map keyed
   by `marca|categoria`) derived once from the `outfit_feedback` table joined against the
   live in-memory catalog. This deliberately **reverses ADR-2 of `outfit-builder`**
   (collection-only). The join happens in the controller layer (which already owns both
   `db` and the live `AggregatedResult`), keeping `OutfitService` a pure function over
   its inputs — consistent with `outfit-builder` ADR-3 (read from in-memory aggregate,
   `OutfitService` stays DB-agnostic).

2. **Style-aware slot eligibility** — the bare `esCalzadoWhitelist(categoria)` gate is
   replaced by a `STYLE_RULES` map keyed by style name. Only the `gym` entry ships, but
   the shape supports adding `casual`/`formal` later without touching `armar()`.

3. **Combo detection in normalization** — `NormalizerService.clasificar()` gains a
   combo pre-check that fires BEFORE the sequential first-match block. A detected
   multi-piece SKU resolves to a new sentinel categoria `"Conjunto"`, which is
   deliberately absent from `OutfitService.CATEGORIA_SLOT` and the calzado whitelist, so
   it is auto-excluded from every outfit slot with zero coupling between the two classes.

## Architecture Decisions

### ADR-1: Feedback model is built in the controller, injected into `armar()`

This **reverses `outfit-builder` ADR-2** (which deferred all weighting) while preserving
`outfit-builder` ADR-3 (`OutfitService` never touches the DB).

| Option | Tradeoff | Decision |
|--------|----------|----------|
| `OutfitService` calls `db.obtenerOutfitFeedback()` itself | Breaks ADR-3 (service stays DB-agnostic, pure over `List<Product>`); injects a DB dependency into a class that is currently dependency-free and unit-testable | Rejected |
| Controller reads feedback rows + builds the model, passes it as a new `armar()` param | Keeps `OutfitService` pure; controller already holds both `db` and `service.getLastResult()`; mirrors how `/api/data` assembles inputs in the controller and hands them to pure filters | **Chosen** |
| Cache the model, rebuild only on new scrape/feedback | Premature optimization; feedback volume is tiny (one app, manual clicks) and the join is O(rows × catalog-index-lookup) | Rejected (note as future tuning) |

**Rationale.** `outfit_feedback` is small and the catalog join needs the *current* live
catalog (a re-scrape changes which URLs resolve). One read + one index build per
`GET /api/outfits` is cheap and always correct. A new `DatabaseService.obtenerOutfitFeedback()`
returns the raw rows; the controller does the url→`Product` join and builds the model.
This means a single DB hit per outfit request — acceptable because, unlike `/api/data`
which can be polled, `/api/outfits` is a deliberate user action (generate / re-roll).

**Pair key.** A "pair" is the string `marca + "|" + categoria`, both taken from the joined
`Product` (NOT from the feedback row, which only stores `url`). Null/blank `marca` or
`categoria` collapses to the empty side of the key (e.g. `"|Remera"`); such rows still
participate so a brandless disliked item is still excludable.

**Conflict resolution (explicit — the spec did not fully pin this).** A pair lands in the
exclude-set if **any** feedback row with `liked=0` touched it, regardless of how many
`liked=1` rows also touched it. Dislike is a HARD, PERMANENT veto and wins over every
like on the same pair. The build order is therefore: (a) scan all rows, accumulate
like-counts per pair into `boostCount`; (b) scan all rows, add any disliked pair to
`excludeSet`; (c) when applying, exclude is checked first, so a pair in both maps is
excluded and its boost never matters. No time decay; global scope (genero ignored when
keying — matches "global, genero-agnostic" in the proposal).

### ADR-2: Exclude applied as a hard filter before the 3-step fallback; boost folded into existing weight

| Option | Tradeoff | Decision |
|--------|----------|----------|
| Exclude inside `weightedRandomPick` (weight 0) | A disliked pair could still be the only candidate and slip through via the `size()==1` shortcut; muddles sampling with eligibility | Rejected |
| Exclude as a candidate-list filter in each slot's Paso 0, applied alongside genero+price, and re-applied through all 3 fallback steps | Excluded pairs never become candidates at any relaxation level; fallback still runs on the *remaining* pool, so a small slot degrades to `partial` rather than serving a disliked pair | **Chosen** |

**Exclude sequencing.** The exclude filter is composed into the existing `filtrar()`
pipeline so it is honored at Paso 0 (genero+band), Paso 1 (band relaxed), and Paso 2
(genero relaxed to unisex). It is applied **before** the 3-step fallback in the sense
that it narrows the per-slot `base` pool up front; the fallback then operates only on
non-excluded products. If exclusion empties a slot, the existing Paso 3 sets `partial=true`
(no fabricated product) — this is the documented small-catalog mitigation.

**Boost formula.** `weightedRandomPick`'s existing weight is `peso = 1/(1+distancia)`.
A liked pair multiplies that weight:

```
peso_final = (1.0 / (1.0 + distancia)) * boostFactor(pair)
boostFactor(pair) = 1.0 + min(likeCount(pair), FEEDBACK_BOOST_CAP) * FEEDBACK_BOOST_STEP
```

Count-based (more likes on the same pair → stronger boost) and capped, so a single pair
cannot dominate a large pool. Proposed tunables, documented as constants the same way
`PRICE_BAND_PCT` is:

- `FEEDBACK_BOOST_STEP = 1.0` (each like adds 1× the base weight)
- `FEEDBACK_BOOST_CAP = 3` (max +3×, i.e. boostFactor ∈ [1.0, 4.0])

So one like ≈ doubles a candidate's weight, three-plus likes ≈ 4×, never unbounded.
Boost is a multiplier on the *existing* price-distance weight, preserving economic
coherence — a liked-but-price-outlier item still won't always win. Pairs with no likes
keep `boostFactor = 1.0` (unchanged behavior, regression-safe).

`weightedRandomPick` must receive the boost-map (or a pre-resolved per-candidate
multiplier). The `candidatos.size()==1` early return is kept but must NOT short-circuit
exclusion — exclusion already removed disliked items upstream, so the single survivor is
by definition allowed.

### ADR-3: `STYLE_RULES` map keyed by style; minimal-but-extensible shape

| Option | Tradeoff | Decision |
|--------|----------|----------|
| Keep `esCalzadoWhitelist(categoria)`, add a Gym `if` | Hardcodes the one style we have; adding Casual/Formal means editing the method body again — exactly the defect the proposal calls out | Rejected |
| `Map<String, Set<String>>` style → calzado-categoria whitelist | Solves the shipped need (Gym calzado), but if a future style also needs to restrict torso/piernas the shape must change → rewrite | Rejected (too narrow) |
| `Map<String, StyleRule>` where `StyleRule` holds an optional per-slot categoria whitelist | Extensible per-slot without rewriting `armar()`; Gym only populates the calzado whitelist, leaving torso/piernas/accesorio unrestricted (null = "no restriction, use base taxonomy") | **Chosen** |

**Concrete shape.**

```java
/** Per-style slot eligibility. A null whitelist for a slot means "no style restriction
 *  beyond the base categoria→slot taxonomy". Only calzado is constrained for Gym v1. */
record StyleRule(Set<String> calzadoWhitelist /* nullable */) { }

private static final Map<String, StyleRule> STYLE_RULES = Map.of(
    "gym", new StyleRule(Set.of(
        "Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
        "Zapatilla Skate", "Zapatilla Urbana", "Sneaker"))
    // Botines/Borcego/Botas/Ojotas intentionally EXCLUDED for Gym.
);
private static final StyleRule DEFAULT_STYLE_RULE = new StyleRule(null); // no restriction
```

The record carries only `calzadoWhitelist` today (YAGNI for the unbuilt styles), but
because eligibility is funneled through a `StyleRule` object rather than a bare method,
adding a `Set<String> torsoWhitelist` field later is an additive change to one record and
one helper — `armar()` and its call sites do not change. This is the proposal's
"build the extension point, don't build the future styles" translated literally: the
*indirection* is the extension point; the *fields* stay minimal.

**Active style determination.** `armar()` gains a `String estilo` parameter defaulting to
`"gym"` (the only value the frontend sends today). The controller passes a constant
`"gym"` for now; no new request param is introduced because the UI exposes only the Gym
tab with logic (Casual/Formal are "Próximamente" placeholders, per `outfit-builder`).
This is the simplest thing consistent with "don't add abstraction beyond what's needed":
the parameter exists so the wiring is ready, but the controller hardcodes its single
real value.

**Method signatures / call sites.**

- `esCalzadoWhitelist(String categoria)` → **removed**, replaced by
  `esCalzadoElegible(StyleRule rule, String categoria)` which returns
  `rule.calzadoWhitelist() == null ? esCalzadoBase(categoria) : rule.calzadoWhitelist().contains(categoria)`,
  where `esCalzadoBase` keeps the old "any footwear categoria" set for styles with no
  calzado restriction. For Gym, the whitelist excludes Botines/Borcego/Botas/Ojotas.
- `slotDe(Product p)` → `slotDe(Product p, StyleRule rule)`: the calzado branch calls
  `esCalzadoElegible(rule, cat)`. If a footwear product fails the style whitelist,
  `slotDe` returns `null` (product is simply not eligible for any slot under that style)
  — it must NOT fall through to `CATEGORIA_SLOT.get(cat)` and re-enter calzado.
- `armar(List<Product>, String generoSolicitado)` →
  `armar(List<Product>, String generoSolicitado, String estilo, FeedbackModel feedback)`.
  The original 2-arg signature MAY be kept as a thin overload delegating with
  `("gym", FeedbackModel.empty())` to limit churn at non-controller call sites and tests.

### ADR-4: Combo detection emits sentinel categoria `"Conjunto"`, decoupling normalizer from outfit logic

| Option | Tradeoff | Decision |
|--------|----------|----------|
| Add combo awareness inside `OutfitService` (detect two-piece picks at assembly time) | Pushes a data-quality problem into the consumer; every future categoria consumer (`/api/facets`, ML, grouping) still sees the wrong `Buzo`/`Traje` label | Rejected |
| In `clasificar()`, detect combos and return a sentinel `"Conjunto"` categoria | Fixes the label at the source (one place); `"Conjunto"` is absent from `CATEGORIA_SLOT` + the calzado whitelist, so `slotDe()` returns null → auto-excluded from all slots with zero coupling | **Chosen** |

**Detection mechanism (both checks, combo pre-check runs first).** Inserted at the very
top of `clasificar()` after the accent-normalization of `t`, BEFORE the TECH/CALZADO/
clothing blocks:

```
// (a) explicit combo tokens — fires regardless of which keyword block would win
if (anyMatch(t, KW_CONJUNTO)) return "Conjunto";
//     KW_CONJUNTO = { "conjunto", "combo", "set ", "set de", "kit ", "pack ", "dos piezas", "2 piezas" }

// (b) dual-block hit — name independently matches a torso keyword AND a piernas keyword
if (matchesTorsoBlock(t) && matchesPiernasBlock(t)) return "Conjunto";
//     torso block = KW_PUFFER..KW_REMERA ; piernas block = KW_CALZA..KW_PANTALON
```

Check (a) catches named combos ("Conjunto Buzo + Jogging", "Combo deportivo", "Set
2 piezas"). Check (b) catches the unnamed dual-keyword defect where a single SKU's name
contains both a torso keyword (`buzo`) and a piernas keyword (`jogging`) — exactly the
proposal's headline bug — even when no combo token is present. Both run before the
sequential first-match block, so the old "torso wins because it's evaluated first"
behavior is preempted. `set`/`kit`/`pack` carry trailing-space/`de` variants to avoid
matching substrings like "settler" or "kitsch".

**Conservatism (over-match risk).** Check (b) deliberately ANDs two *different* blocks;
a legit single torso item ("Buzo oversize") matches only the torso block and is
unaffected. The risk note in the proposal stands: validate `KW_CONJUNTO` and the dual-hit
on real catalog names; if a false positive appears, tighten tokens rather than widening.

**`KW_TRAJE` relationship.** `KW_TRAJE` (a suit = jacket+trousers, currently → `"Traje"`,
a torso slot) is the same class of defect with smaller blast radius. The general
dual-block check does NOT subsume it, because a suit's name ("Traje slim azul") usually
contains only the `traje` token and won't trigger check (b). Decision: leave `KW_TRAJE →
"Traje"` as-is for v1 (a suit landing in the torso slot is visually less broken than two
literal pants, and `"Traje"` is already a torso categoria the UI tolerates), and add a
one-line code comment at the `KW_TRAJE` branch noting it is a known same-class case
deliberately scoped out. The proposal lists `KW_TRAJE` as "covered" only in the sense
that the *mechanism* exists; we explicitly do NOT route `Traje` to `"Conjunto"` because
that would remove suits from outfits entirely, which is a product regression, not a fix.
Flagged here so `sdd-tasks`/`sdd-spec` can confirm the product owner agrees.

### ADR-5: `"Conjunto"` is safe to introduce as a new categoria value

Verified against every downstream consumer of `categoria`:

- **`ResultAggregator.calcularFacets()`** (L150–195) builds `categorias` by counting
  distinct trimmed values into a `LinkedHashMap` — no fixed enum. `"Conjunto"` simply
  becomes one more facet bucket.
- **`/api/data` filtering** (`ApiController` L495–496) and `/api/facets` (L309) compare
  `categoria` with `equalsIgnoreCase` against whatever the client sends — no whitelist.
- **`ml_pipeline.py`** treats `categoria` as a free-form string everywhere
  (`(p.get('categoria') or 'General')`, grouping by value). `"Conjunto"` groups normally.
- **`OutfitService.CATEGORIA_SLOT`** + calzado whitelist do NOT contain `"Conjunto"`, so
  `slotDe()` returns null → excluded from all slots. This is the intended decoupling.

**Residual risk — resolved, in scope.** `ml_pipeline.py` runs an ensemble text+image
re-classifier (~L731, L927 `p['categoria'] = pred_cat`) that can OVERRIDE the scraped
categoria for low-confidence items. A `"Conjunto"` could in theory be re-labeled back to a
single-piece categoria by the ML stage, re-introducing the defect at serving time.
**Decision: add `"Conjunto"` to the ML re-classifier's skip/`genericas` set so it is never
re-classified** — a one-line guard in `ml_pipeline.py`. Confirmed in scope for this change
(not deferred) so the combo fix actually holds at serving time, not just at scrape time.

## Data Flow

```
GET /api/outfits?genero=hombre
  ApiController.outfits()
    r = service.getLastResult()                         (in-memory catalog)
    rows = db.obtenerOutfitFeedback()                   (NEW read query, raw rows)
    feedback = buildFeedbackModel(rows, r.productos())  (NEW controller helper: url→Product join)
        exclude = { marca|categoria : any row liked=0 }
        boost   = { marca|categoria : count(rows liked=1) }   (only if not excluded)
    outfit = outfitService.armar(r.productos(), genero, "gym", feedback)
        1. partition by slot via slotDe(p, STYLE_RULES["gym"])   (Conjunto → null, dropped)
        2. drop products whose marca|categoria ∈ exclude          (hard, before fallback)
        3. per slot: filtrar(genero, band)  → relax band → relax genero  (3-step fallback)
        4. weightedRandomPick(cands, band, boost)  (boost folds into 1/(1+dist))
    ◄─ Outfit{slots[], genero, partial}
    serialize ObjectNode  ──►  OutfitsPanel.jsx

POST /api/outfits/feedback  (UNCHANGED — still write-only)
  ApiController.outfitFeedback() ─► db.guardarOutfitFeedback(...) ─► outfit_feedback

Scrape time (independent path):
  ResultAggregator → NormalizerService.clasificar(name)
     combo pre-check (KW_CONJUNTO | torso∧piernas) → "Conjunto"   (NEW, before first-match)
```

### Sequence: feedback-aware slot sampling + fallback

```
armar(productos, genero, estilo="gym", feedback):
  rule = STYLE_RULES.getOrDefault(estilo, DEFAULT_STYLE_RULE)
  bySlot = partition products by slotDe(p, rule)        # Conjunto/non-eligible → skipped
           AND drop p where key(p) in feedback.exclude  # HARD exclude, all slots
  band = priceBand(median of genero-eligible remaining pool)
  for slot in [torso, piernas, calzado]:
     cands = filtrar(bySlot[slot], genero, band)        # exclude already applied to pool
     if empty: filtrar(..., band=ALL)                   # Paso 1
     if empty: filtrar(..., genero="unisex", band=ALL)  # Paso 2
     if empty: partial=true; skip slot                  # Paso 3
     else pick = weightedRandomPick(cands, band, feedback.boost)
  accesorio = optional, best-effort (exclude applies; no fallback)
  return Outfit(picks, genero, partial)

weightedRandomPick(cands, band, boost):
  for each c: peso = (1/(1+|precio-centro|)) * (1 + min(boost[key(c)], CAP)*STEP)
  sample proportional to peso
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `scraper/.../web/OutfitService.java` | Modify | Add `STYLE_RULES`/`StyleRule`/`DEFAULT_STYLE_RULE`; replace `esCalzadoWhitelist` with `esCalzadoElegible(rule, cat)` + `esCalzadoBase`; `slotDe(p, rule)`; new `FeedbackModel` record (exclude `Set<String>`, boost `Map<String,Integer>`, `empty()`, `keyOf(Product)`); `armar(productos, genero, estilo, feedback)` (+ optional 2-arg overload); apply hard exclude when partitioning; `weightedRandomPick(cands, band, boost)` with `FEEDBACK_BOOST_STEP`/`FEEDBACK_BOOST_CAP` constants |
| `scraper/.../db/DatabaseService.java` | Modify | Add `obtenerOutfitFeedback()` SELECT over `outfit_feedback` returning rows (genero, liked, torso_url, piernas_url, calzado_url, accesorio_url). No schema/DDL change |
| `scraper/.../web/ApiController.java` | Modify | In `outfits()`: read `db.obtenerOutfitFeedback()`, build `FeedbackModel` via url→`Product` join over `r.productos()`, pass `"gym"` + model to `armar()`. New private `buildFeedbackModel(rows, productos)` helper |
| `scraper/.../aggregator/NormalizerService.java` | Modify | Add `KW_CONJUNTO`; combo pre-check at top of `clasificar()` (token match OR dual torso∧piernas block hit) → return `"Conjunto"`; `matchesTorsoBlock`/`matchesPiernasBlock` helpers; one-line `KW_TRAJE` scope note |
| `scraper/.../resources/ml/ml_pipeline.py` | Modify | (ADR-5) add `"Conjunto"` to the ML re-classifier skip/`genericas` set so it isn't overridden at serving time |

## Data Model Changes

None. No DDL change. `outfit_feedback` (id, genero, liked, torso_url, piernas_url,
calzado_url, accesorio_url, created_at) is read-only in this change. `"Conjunto"` is a new
runtime *value* of the existing free-form `productos.categoria` column, not a schema
change. Existing rows keep their old (possibly wrong) categoria until a re-scrape
re-normalizes them — no backfill (per proposal Out of Scope).

## Interfaces / Contracts

`GET /api/outfits` and `POST /api/outfits/feedback` request/response shapes are
**unchanged** — feedback influence is internal to assembly. The only new internal contract:

```java
// OutfitService
record FeedbackModel(Set<String> exclude, Map<String,Integer> boostLikeCount) {
    static FeedbackModel empty();
    static String keyOf(Product p);   // marca|categoria, null-safe
}
Outfit armar(List<Product> productos, String generoSolicitado, String estilo, FeedbackModel feedback);

// DatabaseService — raw rows, controller does the catalog join
record OutfitFeedbackRow(String genero, boolean liked,
                         String torsoUrl, String piernasUrl,
                         String calzadoUrl, String accesorioUrl);
List<OutfitFeedbackRow> obtenerOutfitFeedback();
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|--------------|----------|
| Unit | `keyOf` null-safety; dislike-wins-over-like conflict rule; boost cap; `slotDe` drops `"Conjunto"` and non-whitelisted Gym calzado; combo pre-check on named + dual-keyword inputs | No test infra (Standard mode) — manual via a small `main`/REPL over sample `Product` lists and sample names |
| Integration | Dislike a marca+categoria → that pair absent from many `GET /api/outfits` calls; like → measurably higher frequency; exclude emptying a slot → `partial:true` not crash | Manual: run scraper, POST feedback, inspect repeated GETs |
| Data quality | After fresh scrape, a "Conjunto Buzo + Jogging" SKU has `categoria="Conjunto"` and never appears in any slot; `/api/facets` shows a `Conjunto` bucket without error | Manual: re-scrape, query `/api/data?categoria=Conjunto` and `/api/facets` |
| Regression | No-feedback path produces identical behavior to today (boostFactor=1.0, empty exclude); single non-Gym style request still works via `DEFAULT_STYLE_RULE` | Manual diff of outfit output with empty `outfit_feedback` |

## Migration / Rollout

Read-only addition; no migration, no feature flag. Combo detection affects only future
scrapes — already-scraped rows keep their categoria until re-scraped (documented;
re-scrape is the workaround). Rollback per proposal: revert the four Java edits (and the
optional Python guard), re-inline `esCalzadoWhitelist`, drop the feedback read query and
`armar()` params. `outfit_feedback` rows are untouched. Rebuild:
`cd frontend && npm run build` then `mvn -f scraper/pom.xml clean package -DskipTests`.

## Open Questions

- [ ] **`KW_TRAJE` scope (ADR-4):** confirm with product owner that suits should stay in
      the torso slot (`"Traje"`) rather than being excluded as `"Conjunto"`. Design keeps
      them in; spec/tasks should ratify.
- [ ] **Boost constants (ADR-2):** `FEEDBACK_BOOST_STEP=1.0`, `FEEDBACK_BOOST_CAP=3` are
      first-cut tunables; validate "measurably increases frequency" success criterion on a
      real catalog and adjust like `PRICE_BAND_PCT`.
- [ ] **2-arg `armar()` overload:** keep for compatibility or migrate all call sites to
      the 4-arg form — `sdd-tasks` to decide based on how many internal callers exist.
