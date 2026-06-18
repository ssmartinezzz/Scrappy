# Tasks: Outfit Recommendation Quality

Mode: Standard (no automated test infra in this project). Verification is manual,
per design.md's Testing Strategy table. Each task references the exact design
decision/ADR it implements so `sdd-apply` does not re-derive intent.

This project has no git/VCS. There is no PR-splitting concern. The phase grouping
below exists purely to make `sdd-apply` runnable as multiple sequential batches —
each phase ends in a buildable, runnable state.

## Review Workload Forecast

- Files touched: 5 (`OutfitService.java`, `DatabaseService.java`, `ApiController.java`,
  `NormalizerService.java`, `ml_pipeline.py`) across 2 layers (Java backend, Python ML).
- Chained PRs recommended: N/A — no VCS in this project.
- 400-line budget risk: N/A — no PR size convention applies here.
- Decision needed before apply: **Yes, but it is a batching decision, not a PR-split
  decision.** Recommend `sdd-apply` run as 4 sequential batches matching the phases
  below, because Phase 2 depends on Phase 1's `FeedbackModel` shape, and Phase 3's
  combo-detection is independent of Phases 1–2 but both must be done before Phase 4's
  manual verification can exercise the full design end-to-end.
- Suggested batch boundaries: Phase 1 (feedback read-path), Phase 2 (style-aware
  eligibility, depends on Phase 1's `armar()` signature change), Phase 3 (combo
  detection + ML guard, independent of 1–2, can run before or after them), Phase 4
  (manual verification across all of the above — must run last).

## Open Question Resolution (must confirm before Phase 3 combo-detection tasks)

- [x] **0.1** RESOLVED by product owner: `KW_TRAJE` stays routed to `"Traje"` (torso
      slot) ALWAYS, per design.md ADR-4 — a suit is never treated as a combo, even if
      its name also matches a piernas-block trigger. The spec's "Traje (suit) combo
      case covered" scenario was the contradicting document and has been corrected
      in-place (now "Traje (suit) is exempt from combo resolution", `outfit-builder`
      spec.md) to match this decision. `matchesTorsoBlock()` (Task 3.2) MUST exclude
      `KW_TRAJE` so the dual-block combo check never fires for suits.
- [x] **0.2** `FEEDBACK_BOOST_STEP=1.0` / `FEEDBACK_BOOST_CAP=3` (ADR-2) are first-cut
      tunables. Implement as named constants (same style as `PRICE_BAND_PCT`) so they
      are easy to tune later without re-deriving the formula. No blocking decision
      needed — ship with these defaults, validate in Phase 4 manual verification.
- [x] **0.3** 2-arg `armar()` overload (ADR-3): keep it. Confirmed approach below in
      Phase 2 — keep the 2-arg overload delegating to the 4-arg form with
      `("gym", FeedbackModel.empty())` so no other call site needs to change. Search
      the codebase for existing callers before assuming none exist (Task 2.5).

---

## Phase 1 — Feedback read-path (backend)

Implements: proposal "Feedback-driven sampling"; design ADR-1, ADR-2; spec
`outfit-feedback` Feedback-Driven Sampling requirement (+ MODIFIED `POST
/api/outfits/feedback` and `Feedback Scope Limitation` requirements, which only need
the doc-comment reversal, no behavior change to that endpoint itself).

- [x] **1.1** `DatabaseService.java` — add `OutfitFeedbackRow` record and
      `obtenerOutfitFeedback()` query.
      - Record shape per design.md Interfaces/Contracts: `record OutfitFeedbackRow(String genero, boolean liked, String torsoUrl, String piernasUrl, String calzadoUrl, String accesorioUrl)`.
      - `SELECT genero, liked, torso_url, piernas_url, calzado_url, accesorio_url FROM outfit_feedback` (no schema change — table already exists at L192-203).
      - Return `List<OutfitFeedbackRow>`; empty list if `conn == null` or table empty (mirror existing null-conn guards used elsewhere in this file, e.g. `guardarOutfitFeedback` L843).
      - No write-path change. `guardarOutfitFeedback()` (L841-859) stays untouched.

- [x] **1.2** `OutfitService.java` — add `FeedbackModel` record and `keyOf()`.
      - Per design.md Interfaces/Contracts: `record FeedbackModel(Set<String> exclude, Map<String,Integer> boostLikeCount)` with `static FeedbackModel empty()` and `static String keyOf(Product p)`.
      - `keyOf(Product p)` returns `marca + "|" + categoria`, both null-safe: null/blank `marca` or `categoria` collapses to the empty string for that side of the key (ADR-1 "Pair key" — e.g. `"|Remera"`), per spec scenario language ("brandless disliked item is still excludable").
      - `FeedbackModel.empty()` returns an instance with an empty `Set` and empty `Map` — this is the value the 2-arg `armar()` overload delegates with (Task 2.5), and is also the "no feedback yet" regression-safety case (Testing Strategy "Regression" row).

- [x] **1.3** `ApiController.java` — add `buildFeedbackModel(rows, productos)` helper.
      - Build the url→`Product` index from `r.productos()` (live catalog), once per call.
      - For each `OutfitFeedbackRow`, resolve each non-null slot URL (`torsoUrl`, `piernasUrl`, `calzadoUrl`, `accesorioUrl`) against the index. Per spec "Feedback references a delisted product" scenario: if a URL is not found in the live catalog, skip that URL silently (no error, no log) — do not abort the whole row, since other slot URLs on the same row may still resolve.
      - Conflict resolution order, per ADR-1 exactly: (a) scan all rows, accumulate per-pair like-counts into `boostLikeCount` (`liked=1` rows only); (b) scan all rows again, add every pair touched by a `liked=0` row into `exclude`; (c) do NOT remove a pair from `boostLikeCount` even if it also lands in `exclude` — the consuming code in `OutfitService` checks `exclude` first, so a pair present in both is excluded and its boost value is simply never read. This matches design.md's "exclude is checked first, so a pair in both maps is excluded and its boost never matters."
      - Genero is ignored entirely when building keys (global scope, per spec "Feedback-Driven Sampling" requirement: "MUST NOT vary by genero"). Do not key by `row.genero()` anywhere in this method.
      - Return a populated `OutfitService.FeedbackModel`.

- [x] **1.4** `ApiController.java` — wire into `outfits()` (currently L675-699).
      - Before calling `armar()`: `var rows = db.obtenerOutfitFeedback();` then `var feedback = buildFeedbackModel(rows, r.productos());`.
      - Change the call site from `outfitService.armar(r.productos(), genero)` to the 4-arg form once Phase 2 lands it (this task only adds the feedback read + model build; the actual `armar()` call-site signature change happens in Task 2.5 to keep this task buildable in isolation — if Phase 2 has not landed yet, build `feedback` here but do not change the `armar()` call yet, OR do both in the same apply batch if running Phase 1+2 together).

- [x] **1.5** `OutfitService.java` — apply hard exclude in partitioning (no boost yet —
      boost depends on `weightedRandomPick` change, Task 2.x... actually boost is
      independent of style; see note). Per ADR-2 "Exclude sequencing": the exclude
      filter must be composed into the `filtrar()` pipeline so it is honored at every
      relaxation step (Paso 0 genero+band, Paso 1 band-relaxed, Paso 2 genero-relaxed),
      narrowing the per-slot `base` pool BEFORE the 3-step fallback runs, not inside
      `weightedRandomPick`. Concretely: when building `bySlot` (current L122-133), also
      drop any product `p` where `FeedbackModel.keyOf(p)` is in `feedback.exclude()` —
      this happens once, before slot fallback, so all 3 fallback steps in `armar()`
      (L150-160) operate only on the already-excluded-filtered pool, matching the spec
      scenario "Feedback exclusion empties a slot and fallback recovers it" (price-band
      relaxes, exclusion does not).
      - Do NOT implement boost in this task — that requires `weightedRandomPick`'s
        signature to change, which Task 2.6 owns, to keep this task's diff minimal
        and independently buildable.

- [x] **1.6** Manual verification checkpoint for Phase 1 (do not skip — see Phase 4 for
      the full pass, but this is a quick smoke check before moving to Phase 2):
      - Build: `cd frontend && npm run build` then `mvn -f scraper/pom.xml clean package -DskipTests`.
      - Run the jar, trigger a scrape so `getLastResult()` is populated.
      - `curl -X POST http://localhost:3000/api/outfits/feedback -H "Content-Type: application/json" -d "{\"genero\":\"hombre\",\"liked\":false,\"slots\":[{\"slot\":\"calzado\",\"url\":\"<a real product URL from /api/data>\"}]}"` — confirm `{"ok":true}`.
      - Inspect `scraper.db` directly (e.g. via a SQLite browser or `sqlite3 scraper.db "SELECT * FROM outfit_feedback;"`) — confirm one row with `liked=0` and the URL in `calzado_url`.
      - `curl http://localhost:3000/api/outfits?genero=hombre` repeated ~10 times — confirm the disliked product's `marca`+`categoria` never appears in the `calzado` slot of any response (cross-check `marca`/`categoria` fields in the JSON against the disliked product's catalog entry via `/api/data?q=<name>`).

## Phase 2 — Style-aware slot eligibility

Implements: proposal "Style-aware slot eligibility"; design ADR-2 (boost half), ADR-3;
spec `outfit-builder` MODIFIED "Gym Outfit Source Filtering", MODIFIED "Fallback
Policy", ADDED "Style-Aware Slot Eligibility".

Depends on Phase 1 (`FeedbackModel` must exist before `armar()`'s 4-arg signature is
finalized) — if Phase 1 and 2 run as separate `sdd-apply` batches, Phase 1 must
complete first.

- [x] **2.1** `OutfitService.java` — add `StyleRule` record, `STYLE_RULES` map,
      `DEFAULT_STYLE_RULE` constant. Exact shape per design.md ADR-3 "Concrete shape":
      ```java
      record StyleRule(Set<String> calzadoWhitelist /* nullable */) { }
      private static final Map<String, StyleRule> STYLE_RULES = Map.of(
          "gym", new StyleRule(Set.of(
              "Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
              "Zapatilla Skate", "Zapatilla Urbana", "Sneaker"))
      );
      private static final StyleRule DEFAULT_STYLE_RULE = new StyleRule(null);
      ```
      Note: spec's ADDED "Style-Aware Slot Eligibility" requirement says Gym calzado
      matches "`Zapatilla*` prefix family, or `categoria = "Sneaker"`" — the concrete
      `Set.of(...)` above is a finite enumeration consistent with the categorias that
      currently exist in `clasificar()`'s Zapatilla branch (L680-687: Running,
      Entrenamiento, Skate, Urbana, bare "Zapatilla", plus Sneaker). Use the prefix-style
      `categoria.startsWith("Zapatilla")` check rather than the literal `Set.of(...)`
      enumeration in the actual eligibility method (Task 2.2) so any future
      `"Zapatilla <NewVariant>"` categoria value added to `clasificar()` is
      automatically Gym-eligible without touching `STYLE_RULES` — this matches the
      spec's literal "`Zapatilla*` prefix family" wording and is more robust than a
      closed enum.

- [x] **2.2** `OutfitService.java` — replace `esCalzadoWhitelist(String)` (current
      L90-98) with two methods:
      - `esCalzadoBase(String categoria)`: the OLD whitelist body exactly as it is today
        (Zapatilla-prefix OR Botines/Borcego/Botas/Ojotas/Sneaker) — this becomes the
        "no style restriction" fallback used by `DEFAULT_STYLE_RULE`.
      - `esCalzadoElegible(StyleRule rule, String categoria)`: `return rule.calzadoWhitelist() == null ? esCalzadoBase(categoria) : (categoria != null && (categoria.startsWith("Zapatilla") ? rule.calzadoWhitelist().stream().anyMatch(categoria::equals) : rule.calzadoWhitelist().contains(categoria)));` — simplify to: if `calzadoWhitelist()` is non-null, use `rule.calzadoWhitelist().contains(categoria)` directly since the Gym set already enumerates every Zapatilla variant + Sneaker explicitly (per Task 2.1's note, prefer enumerating `Zapatilla*` membership via a `startsWith` helper inside the Gym rule's contains-check if new variants are a concern — but the simplest correct implementation per the design's literal pseudocode in ADR-3 line 143 is the plain `.contains(categoria)` call; do not over-engineer here).
      - Delete the old `esCalzadoWhitelist` entirely once both replacements are wired (Task 2.3) — do not leave dead code.

- [x] **2.3** `OutfitService.java` — change `slotDe(Product p)` (current L78-83) to
      `slotDe(Product p, StyleRule rule)`:
      - `if (esCalzadoElegible(rule, cat)) return SLOT_CALZADO;` replaces the old
        `esCalzadoWhitelist(cat)` call.
      - Per ADR-3 "Method signatures": if a footwear product fails the style whitelist
        (e.g. Botines under Gym), `slotDe` MUST return `null` — it must NOT fall through
        to `CATEGORIA_SLOT.get(cat)`, because `CATEGORIA_SLOT` has no calzado entries
        anyway (calzado is handled entirely via the whitelist branch, never via the map
        per current L51-54 vs L81-82 logic) — confirm this stays true after the edit:
        Botines/Borcego/Botas/Ojotas/Sneaker are NOT in `CATEGORIA_SLOT`'s
        `buildCategoriaSlotMap()` (L51-54 lists them as calzado in a comment-adjacent
        block but they are added to the SLOT_CALZADO bucket of the map at L53 — verify
        whether this becomes redundant/conflicting with the new whitelist-only logic;
        if `buildCategoriaSlotMap()` still maps Botines→SLOT_CALZADO directly, a Gym
        request for a Botines product would incorrectly resolve via the map fallback
        if the whitelist check is structured as "try whitelist, else try map" — the
        fix is to ensure the calzado check happens via `esCalzadoElegible` BEFORE any
        map fallback for calzado categorias, and that the map's calzado entries are
        either removed or made unreachable for calzado-classified categorias so they
        cannot bypass the style gate. Resolve this exact ordering/redundancy when
        writing the method — do not let `CATEGORIA_SLOT` provide a second path into
        `SLOT_CALZADO` that skips `esCalzadoElegible`.

- [x] **2.4** `OutfitService.java` — update all `slotDe(p)` call sites (currently one,
      inside `armar()`'s partition loop) to `slotDe(p, rule)`, where `rule` is resolved
      via `STYLE_RULES.getOrDefault(estilo, DEFAULT_STYLE_RULE)` at the top of `armar()`.

- [x] **2.5** `OutfitService.java` — change `armar()` signature per ADR-3 "Method
      signatures" and ADR-1: `armar(List<Product> productos, String generoSolicitado, String estilo, FeedbackModel feedback)`.
      - Add a 2-arg overload: `public Outfit armar(List<Product> productos, String generoSolicitado) { return armar(productos, generoSolicitado, "gym", FeedbackModel.empty()); }` — per Open Question 0.3, keep this overload (do not remove it) so any non-controller call sites (search the codebase first: `grep -rn "\.armar(" scraper/src` or equivalent) keep compiling without edits. If the search finds zero other call sites besides `ApiController`, still keep the overload per the design's explicit guidance ("MAY be kept... to limit churn") — removing it has no benefit and the design author already decided to keep it.

- [x] **2.6** `OutfitService.java` — fold boost into `weightedRandomPick` (this is the
      other half of ADR-2, deferred from Phase 1 Task 1.5 because it needs the 4-arg
      `armar()` plumbing to pass `feedback.boostLikeCount()` through):
      - Add constants: `private static final double FEEDBACK_BOOST_STEP = 1.0;` and
        `private static final int FEEDBACK_BOOST_CAP = 3;` (same style/visibility as
        `PRICE_BAND_PCT` at L35) — see Open Question 0.2.
      - Change signature: `weightedRandomPick(List<Product> candidatos, double[] band, Map<String,Integer> boostLikeCount)`.
      - Per ADR-2 "Boost formula" exactly:
        ```java
        double likeCount = boostLikeCount.getOrDefault(FeedbackModel.keyOf(c), 0);
        double boostFactor = 1.0 + Math.min(likeCount, FEEDBACK_BOOST_CAP) * FEEDBACK_BOOST_STEP;
        double peso = (1.0 / (1.0 + distancia)) * boostFactor;
        ```
      - Per ADR-2 "must NOT short-circuit exclusion": keep the `candidatos.size() == 1` early-return (current L217) — it is safe because exclusion already ran upstream in `armar()`'s partitioning (Task 1.5), so a single survivor here is by definition not an excluded pair. Do not add boost logic to the size-1 branch (it returns immediately, boost is irrelevant for a forced single pick).
      - Update both call sites (`elegido = weightedRandomPick(cands, band)` at current L168, and `accesorio = weightedRandomPick(accesoriosElegibles, band)` at current L177) to pass `feedback.boostLikeCount()`.

- [x] **2.7** `ApiController.java` — finalize the `outfits()` call site (this completes
      Task 1.4's deferred half): `OutfitService.Outfit outfit = outfitService.armar(r.productos(), genero, "gym", feedback);` per design.md Data Flow ("controller passes a constant `"gym"`... no new request param"). Do not add a `style`/`estilo` query parameter to the `@GetMapping("/outfits")` method — the design explicitly scopes this out (UI only exposes Gym today).

- [x] **2.8** Manual verification checkpoint for Phase 2:
      - Rebuild (same two commands as 1.6).
      - `curl http://localhost:3000/api/outfits?genero=hombre` repeated ~15-20 times — inspect each response's `calzado` slot `categoria` field. Confirm it is NEVER `"Botines"`, `"Borcego"`, `"Botas"`, or `"Ojotas"` (per spec scenario "Dress footwear excluded from Gym calzado").
      - `curl "http://localhost:3000/api/data?categoria=Botines&size=5"` — confirm Botines products genuinely exist in the catalog (so the above is a real negative, not an artifact of an empty catalog).
      - Re-run the Phase 1 dislike/like smoke test (1.6) again now that boost is wired — generate ~20 outfits after a `liked=true` submission for a specific marca+categoria pair, and eyeball whether that pair's `calzado` or `torso` slot value appears more often than other pairs (qualitative check; exact frequency math is not required, just "noticeably more often" per the spec's "measurably increases frequency" language — this is the best available check without an automated histogram, given Standard mode).

## Phase 3 — Combo detection + ML pipeline guard

Implements: proposal "Combo-product detection"; design ADR-4, ADR-5; spec
`outfit-builder` ADDED "Combo Product Categorization" requirement.

Independent of Phases 1-2 (touches `NormalizerService.java` and `ml_pipeline.py` only,
no `OutfitService`/`ApiController`/`DatabaseService` overlap) — can be implemented and
apply-batched before, after, or interleaved with Phases 1-2. Listed third here only
because Phase 4's full verification needs all three done.

- [x] **3.1** `NormalizerService.java` — add `KW_CONJUNTO` keyword array near the other
      `KW_*` arrays (e.g. adjacent to `KW_BUZO` at L135 or `KW_TRAJE` at L169). Per
      ADR-4 exactly: `{ "conjunto", "combo", "set ", "set de", "kit ", "pack ", "dos piezas", "2 piezas" }`.
      Note the deliberate trailing-space/`-de` variants on `set`/`kit`/`pack` to avoid
      substring false-positives ("settler", "kitsch", "package") — preserve this exact
      token shape, do not "clean it up" to bare `"set"`/`"kit"`/`"pack"`.

- [x] **3.2** `NormalizerService.java` — add `matchesTorsoBlock(String t)` and
      `matchesPiernasBlock(String t)` helpers. Per ADR-4 "Detection mechanism":
      - `matchesTorsoBlock`: ORs `anyMatch(t, KW)` across the same keyword arrays used
        in the torso section of `clasificar()` (current L696-708: `KW_PUFFER`,
        `KW_PILOTO`, `KW_SACO`, `KW_CHALECO`, `KW_CAMPERA`, `KW_SWEATER`,
        `KW_BUZO`, `KW_CASACA`, `KW_CHOMBA`, `KW_MUSCULOSA`, `KW_CAMISA`, `KW_REMERA`).
        Per Open Question 0.1 (resolved): `KW_TRAJE` is deliberately EXCLUDED from this
        list — a suit must never be routed to the combo categoria via the dual-block
        path, regardless of any piernas-block keyword also present in its name.
      - `matchesPiernasBlock`: ORs `anyMatch(t, KW)` across the piernas section
        keyword arrays (current L711-720: `KW_CALZA`, `KW_BAGGY`, `KW_JEAN`,
        `KW_JOGGING`, `KW_BERMUDA`, `KW_SHORT`, `KW_VESTIDO`, `KW_ENTERITO`,
        `KW_POLLERA`, `KW_PANTALON`).

- [x] **3.3** `NormalizerService.java` — insert the combo pre-check at the very top of
      `clasificar()` (current L643-650), immediately after the accent-normalization
      that produces `t` (current L646-649) and BEFORE the TECH block (current L652):
      ```java
      if (anyMatch(t, KW_CONJUNTO)) return "Conjunto";
      if (matchesTorsoBlock(t) && matchesPiernasBlock(t)) return "Conjunto";
      ```
      Per ADR-4: both checks run unconditionally before any other keyword block, so the
      old "torso wins because it's evaluated first" behavior is fully preempted for
      combo-signaling names. Do not place this check after the TECH/CALZADO blocks —
      ADR-4 specifies "BEFORE the TECH/CALZADO/clothing blocks" exactly.

- [x] **3.4** `NormalizerService.java` — add a one-line code comment at the `KW_TRAJE`
      branch (current L698: `if (anyMatch(t, KW_TRAJE)) return "Traje";`) noting:
      "intentionally excluded from combo detection — suits always resolve to Traje,
      see ADR-4 / tasks.md 0.1 (product owner confirmed)."

- [x] **3.5** `resources/ml/ml_pipeline.py` — add `"conjunto"` to the `genericas` set
      used as the ML re-classifier's skip condition. Current location: L815
      `genericas = {'indumentaria','general','ropa','pc & tech','tecnologia',''}`.
      Per ADR-5 "Residual risk — resolved, in scope": this set is checked at L829
      (`(p.get('categoria') or '').strip().lower() in genericas`, part of the condition
      gating which products even enter the re-classification candidate pool) and again
      at L925 (`if cat_actual.lower() in genericas or confianza >= 0.92:` — gates
      whether the predicted category actually overwrites `p['categoria']` at L927).
      Adding `'conjunto'` to `genericas` means: (a) at L829, a `"Conjunto"` product
      becomes eligible for re-classification consideration same as any generic
      category (this is acceptable per ADR-5 — the actual override-prevention is at
      925); (b) at L925, since `cat_actual.lower() == 'conjunto'` would be in
      `genericas`, the `or` condition becomes trivially true regardless of `confianza`,
      which is the OPPOSITE of what we want — re-read ADR-5's exact requirement: "add
      `Conjunto` to the ML re-classifier's skip/`genericas` set so it is never
      re-classified." The `genericas` set as used at L925 currently means "these
      categories ARE allowed to be overridden generously" (low-confidence override
      threshold), not "these are protected from override." **Verify this control-flow
      claim against the actual code before implementing** — if `genericas` membership
      at L925 means "easier to override," adding `"conjunto"` there would make
      `"Conjunto"` MORE likely to be reclassified, not less, contradicting ADR-5's
      intent. In that case, the correct fix is a NEW, separate guard — e.g. an early
      `continue`/`skip` check before L862 (`cat_actual = ...`) that excludes any product
      whose `cat_actual.lower() == 'conjunto'` from re-classification entirely,
      regardless of confidence. Read L805-930 in full before writing this task's code
      and choose whichever guard actually achieves "never re-classified," even if it
      means NOT literally adding to the `genericas` set as ADR-5's prose suggests —
      the design's INTENT (never re-classified) governs over its literal mechanism
      suggestion if the mechanism turns out to be wrong on inspection.

- [x] **3.6** Manual verification checkpoint for Phase 3 (data-quality layer, requires
      a fresh scrape — per spec scenario "Fix does not retroactively correct existing
      rows", a server restart alone is not enough):
      - Identify or temporarily note a real catalog product name that should trigger
        each signal (search current catalog via `curl "http://localhost:3000/api/data?q=conjunto&size=10"` and `curl "http://localhost:3000/api/data?q=buzo&size=20"` to find natural candidates, since `clasificar()` runs on real scraped names, not a controlled test name).
      - Trigger a fresh scrape: `curl -X POST "http://localhost:3000/api/scrape?precioMin=0&precioMax=999999"`, poll `curl http://localhost:3000/api/status` until `DONE`.
      - `curl "http://localhost:3000/api/data?categoria=Conjunto&size=20"` — confirm a `Conjunto` bucket exists and is non-empty if any combo-signaling product names exist in the source catalogs.
      - `curl http://localhost:3000/api/facets` — confirm `categorias` includes `"Conjunto"` with a count, and the endpoint does not error.
      - `curl "http://localhost:3000/api/outfits?genero=hombre"` repeated ~15 times — confirm no slot's `categoria` is ever `"Conjunto"` (it must never be selectable, per ADR-5's decoupling claim).
      - Inspect `scraper.db` directly for a few products previously known/suspected to be combo SKUs (if any were identified pre-scrape) — confirm `categoria='Conjunto'` post-scrape.
      - If `ml_pipeline.py` logs are visible (stdout/stderr via the app's console or `scraper.log`), check for the `"[ML] Refinando categorias..."` log line (current L814) and confirm no `Conjunto` product's `categoria` flips to something else in the same run (cross-check `/api/data?categoria=Conjunto` count before/after the ML stage if the pipeline logs intermediate counts, or simply confirm the count stays stable across two consecutive scrapes of the same source data).

## Phase 4 — Full manual verification pass

Implements: design.md Testing Strategy table (all four rows), proposal Success
Criteria checklist. Run only after Phases 1-3 are all applied and the project
rebuilds cleanly.

- [x] **4.1** Rebuild from clean: `cd frontend && npm run build` then
      `mvn -f scraper/pom.xml clean package -DskipTests`. Confirm zero compile errors
      across all 4 modified Java files plus the Python file syntax-checks
      (`python -m py_compile scraper/src/main/resources/ml/ml_pipeline.py` or
      equivalent, since Python errors only surface at subprocess-run time otherwise).

- [x] **4.2** Success Criterion: "Disliking a product's marca+categoria removes that
      pair from all future generated outfits (any genero)."
      - POST a dislike for a specific marca+categoria pair (pick a calzado product with
        a non-empty `marca` from `/api/data`).
      - `curl http://localhost:3000/api/outfits?genero=hombre`,
        `curl http://localhost:3000/api/outfits?genero=mujer`,
        `curl http://localhost:3000/api/outfits?genero=unisex` — each x5 repetitions.
        Confirm the disliked marca+categoria never appears in any of the 15 responses.

- [x] **4.3** Success Criterion: "Liking a product's marca+categoria measurably
      increases its sampling frequency."
      - POST a like for a marca+categoria pair that has multiple same-category
        competitors in the catalog (check via `/api/data?categoria=X&marca=Y` count
        vs other marcas in the same categoria).
      - Generate 30+ outfits via repeated `/api/outfits` calls, tally how often the
        boosted pair appears in its slot vs. how often it would appear under roughly
        uniform odds (1/N where N = candidate count in that slot+band). Confirm the
        boosted pair's observed frequency is clearly above the 1/N baseline.

- [x] **4.4** Success Criterion: "Gym outfits never include Botines/Borcego/Botas;
      only athletic footwear." Already covered by Task 2.8 — re-run once more after
      Phase 3 lands in case combo detection changed which products remain classified
      as calzado at all.

- [x] **4.5** Success Criterion: "The style mechanism accepts a new style entry without
      rewriting armar()." This is a structural/code-review check, not a runtime check:
      open `OutfitService.java` and confirm `STYLE_RULES` is the only place that would
      need a new `Map.of(...)` entry to add e.g. `"casual"` — confirm `armar()`,
      `slotDe()`, and `esCalzadoElegible()` contain no `if ("gym".equals(estilo))`
      branching (style-specific logic must live entirely inside the `StyleRule` data,
      not in control flow).

- [x] **4.6** Success Criterion: "After a fresh scrape, a 'Conjunto Buzo + Jogging'-type
      SKU no longer mislabels as a torso item that collides with a separate pants
      pick." Covered by Task 3.6. Additionally: generate outfits and visually confirm
      no outfit's torso+piernas pair "looks like" two bottoms or two tops (qualitative
      eyeball check against the `nombre` field of both slot picks in a few `/api/outfits` responses).

- [x] **4.7** Success Criterion: "No outfit_feedback schema change; no DB backfill
      required." Code-review check: confirm `DatabaseService.java`'s `CREATE TABLE
      outfit_feedback` DDL (L192-203) is byte-for-byte unchanged, and confirm no new
      migration/backfill code was added anywhere in this change.

- [x] **4.8** Regression check (Testing Strategy table, "Regression" row): with an
      empty `outfit_feedback` table (fresh DB or a row-free table), confirm
      `/api/outfits` behaves identically to pre-change behavior — `boostFactor` is
      always `1.0` (no boost applied) and `exclude` is always empty (no products
      removed). This validates `FeedbackModel.empty()` and the 2-arg `armar()` overload
      are wired correctly as true no-ops.

- [x] **4.9** Update `CLAUDE.md`'s "Problemas conocidos / pendientes" table if any of
      the items there are now resolved by this change (none currently listed map
      directly to this change's scope, but re-check after implementation in case the
      combo-detection fix incidentally affects a listed issue, e.g. the "Clasificación
      oferta_real inconsistente" row, which touches `categoria`-adjacent ML logic).
