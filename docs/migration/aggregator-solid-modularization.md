# Aggregator SOLID Modularization — Migration Tracker

Behavior-preserving package split of `ar.scraper.aggregator` (`NormalizerService`,
`GroupingService`, `ResultAggregator`) into single-responsibility collaborators.
Zero observable behavior change; `Product` record shape, `ml_pipeline.py` JSON
contract, and SQLite schema stay untouched. See SDD artifacts
`sdd/aggregator-solid-modularization/{spec,design,tasks}` for full detail.

Delivery: single feature branch `refactor/aggregator-solid-modularization`,
ONE PR to `master`, `size:exception` accepted by the user. Strict TDD active —
every work unit below leaves `mvn test` green.

- [x] Slice 1: GroupingService characterization tests + scaffold
- [x] Slice 2: Grouping package split (ProductGroup, ProductIdentity, JaccardSimilarity, AccentStripper)
- [x] Slice 3: Data/predicate holders (GarmentTaxonomy, CategoryGroups, SiteClassification, NonTextileGuard)
- [x] Slice 4: PackQuantityDetector (literal cut-paste)
- [x] Slice 5: CategoryClassifier
- [x] Slice 6: BrandExtractor + GenderResolver + SizeNormalizer
- [x] Slice 7: SubcategoryResolver
- [x] Slice 8: RubroResolver + GymratTagger + orchestrator cleanup + NormalizerServiceTestFactory
- [x] Slice 9 (final): ResultAggregator decomposition + FacetCalculator extraction

## Slice detail

### Slice 1 — Grouping Safety Net + Migration Tracker Scaffold (DONE)
- `GroupingServiceCharacterizationTest` added under
  `scraper/src/test/java/ar/scraper/aggregator/`, covering: single-site
  passthrough, cross-site grouping with shortest-name canonicalization,
  Jaccard 0.5 miss (Air Force vs Air Max), `soloMultiSitio` filter,
  price-ascending ordering, `ahorroPct`/`precioMaximo` math, same-site
  never-merge guard.
- Confirmed green against the CURRENT (pre-split) `GroupingService`.
- This file created.

### Slice 2 — Grouping Package Split (DONE)
- `ar.scraper.aggregator.text.AccentStripper` — shared byte-identical
  6-replacement accent chain (ADR-4).
- `ar.scraper.aggregator.grouping.ProductGroup` — promoted top-level, getters
  frozen (`ApiController` JSON mapping unaffected).
- `ar.scraper.aggregator.grouping.ProductIdentity` — extracted from
  `calcularIdentidad`.
- `ar.scraper.aggregator.grouping.JaccardSimilarity` — extracted from
  `subAgruparPorJaccard`/`jaccardSimilarity`/`palabrasSignificativas`.
- `ar.scraper.aggregator.grouping.GroupingService` — thin orchestrator,
  constructor-injects `ProductIdentity` + `JaccardSimilarity`; public
  `agrupar(List<Product>, boolean)` signature unchanged.
- `ApiController` field/constructor updated to the new package path only
  (call site `grouping.agrupar(...)` untouched).
- Characterization suite from Slice 1 updated to import from the new
  package and stays green; add `ProductIdentityTest`, `JaccardSimilarityTest`.

### Slice 3 — Shared Data/Predicate Holders (DONE)
- `ar.scraper.aggregator.normalize.GarmentTaxonomy` — all `KW_*` keyword
  arrays + `concatKeywords` + `TORSO_KEYWORDS_FLAT`/`PIERNAS_KEYWORDS_FLAT`
  derivations, moved verbatim.
- `ar.scraper.aggregator.normalize.CategoryGroups` — `esCalzado`,
  `esIndumentariaOCalzado`, supplement-category predicate.
- `ar.scraper.aggregator.normalize.SiteClassification` — `TECH_SITIOS`,
  `SUPPL_SITIOS`, `GYM_SITIOS`, `GYM_MARCAS`, `SITIOS_PREMIUM`, `sitioKey`
  normalization.
- `ar.scraper.aggregator.normalize.NonTextileGuard` — `esClaramenteNoTextil`
  + `NO_TEXTIL_INICIO`.
- All four registered as `@Component` beans; pure relocation of constants —
  `NormalizerService` still uses `new NormalizerService()` (no constructor
  change yet); `NormalizerServiceTest` stays green unmodified.
- RISK MITIGATION (ADR-1 guard): guard test asserting
  `TORSO_KEYWORDS_FLAT`/`PIERNAS_KEYWORDS_FLAT` are byte-identical to a
  frozen snapshot taken at extraction time.

### Slice 4 — PackQuantityDetector (DONE)
- `ar.scraper.aggregator.normalize.PackQuantityDetector` — literal cut-paste
  of `detectarCantidadUnidades` + all patterns (`PACK_KEYWORD_COUNT`,
  `KEYWORD_NEAR_X_COUNT`, `N_PIEZAS`, `RANGO_TALLE`, `CONTEO_COLOR`,
  `GARMENT_PLURAL_ROOTS`/`PATTERNS`, `COMBO_CONNECTOR`,
  `MAX_COMBO_CONNECTOR_GAP`, `firstMatchSpan`,
  `matchesTorsoPiernasComboConConector`, `esRangoDeTalle`, `esConteoDeColor`,
  `cap`). No logic rewrite. Reads `TORSO_KEYWORDS_FLAT`/`PIERNAS_KEYWORDS_FLAT`
  from `GarmentTaxonomy` and uses `NonTextileGuard`. Public entry point:
  `detectar(String, String)`.
- RISK MITIGATION: a temporary diff-check test compared old
  `NormalizerService.detectarCantidadUnidades` output vs new
  `PackQuantityDetector.detectar` output across the full fixture set (all
  migrated pack/combo test cases + a broader corpus of ~70 product-name
  strings) — zero deltas confirmed before the old method was removed.
- `NormalizerService` field-initializes a `PackQuantityDetector` instance
  (constructor injection lands in Work Unit 8) and delegates
  `cantidadUnidades` resolution to it.
- Pack/combo tests migrated to `PackQuantityDetectorTest`; originals removed
  from `NormalizerServiceTest` in the same commit.

### Slice 5 — CategoryClassifier (DONE)
- `ar.scraper.aggregator.normalize.CategoryClassifier` — moved `clasificar`,
  `normalizarCategoria`, `anyMatch`, `tieneIndicadorPeso`/`PESO_VOLUMEN`,
  context guards (`esContextoBotin/Borcego/Ojota/Chomba`),
  `matchesTorsoBlock`/`matchesPiernasBlock`, and `capitalize` (private
  utility used only by the raw-category cleanup fallback). If-chain and
  keyword arrays relocated, not rewritten. Reads keywords from
  `GarmentTaxonomy` — confirmed same instance/source consumed by
  `PackQuantityDetector` (task 5.2 risk mitigation), no per-class copies.
- `NormalizerService` field-initializes a `CategoryClassifier` instance and
  delegates `categoria` resolution to it. `esGymrat` keeps a small local
  `anyMatch` copy (trivial 3-line loop, not part of the ADR-1 taxonomy-drift
  surface) until it's extracted into `GymratTagger` in Work Unit 8.
- Classifier tests (`clasificar*`, context guards, timberland/borcego,
  puffer/inflador/termica, munecuera/shaker, alimentos/salsa,
  creatina/whey/magnesio/preWorkout/bcaa/vitaminas,
  `productoConPesoSinKeyword…`) migrated to `CategoryClassifierTest`;
  originals removed from `NormalizerServiceTest` in the same commit.

### Slice 6 — BrandExtractor + GenderResolver + SizeNormalizer (DONE)
- `ar.scraper.aggregator.normalize.BrandExtractor` — moved `extraerMarca` +
  `MARCAS` + `MARCA_PATTERNS`. Design-vs-actual-code note: the original
  method never called `normalizarAcentos` (word-boundary regex operates
  directly on lower-cased text), so there was no accent chain to delegate
  to `AccentStripper` here.
- `ar.scraper.aggregator.normalize.GenderResolver` — moved `normalizarGenero`
  + `FEMININE_CODED_CATEGORIES`. Its local accent-normalization helper now
  delegates to `AccentStripper.strip` (ADR-4) instead of duplicating the
  6-replacement regex chain — same output, one fewer copy.
- `ar.scraper.aggregator.normalize.SizeNormalizer` — moved `normalizarTalles`,
  `normalizarTalle`, `TALLE_MAP`. Design-vs-actual-code note: `normalizarTalle`
  never called `capitalize` (it upper-cases directly), so nothing to move
  from that utility (already relocated to `CategoryClassifier` in Slice 5).
- `NormalizerService` field-initializes the three new collaborators and
  delegates `marca`/`genero`/`talles` resolution to them. `normalizarAcentos`
  stays in `NormalizerService` (private) — still used by `resolverSubCategoria`
  until Slice 7.
- Brand tests migrated to `BrandExtractorTest`, gender tests to
  `GenderResolverTest`; originals removed from `NormalizerServiceTest` in the
  same commit. No pre-existing `SizeNormalizer` tests to migrate.

### Slice 7 — SubcategoryResolver (DONE)
- `ar.scraper.aggregator.normalize.SubcategoryResolver` — moved
  `resolverSubCategoria` + `SUBCATEG_TIER1` + `SUBCATEG_TIER2`. Local
  accent-normalization helper delegates to `AccentStripper.strip` (ADR-4)
  instead of duplicating the regex chain — the last consumer of
  `NormalizerService`'s private `normalizarAcentos`, which is now fully
  removed from `NormalizerService`.
- `NormalizerService` field-initializes a `SubcategoryResolver` instance and
  delegates `subCategoria` resolution to it.
- `resolverSubCategoria*` (tier1/tier2/word-boundary/accent) tests migrated
  to `SubcategoryResolverTest`; originals removed from `NormalizerServiceTest`
  in the same commit. `productLegacyConstructorDefaultsSubCategoriaToEmpty`
  stays in `NormalizerServiceTest` — it tests `Product`'s legacy constructor
  directly, not `resolverSubCategoria`.

### Slice 8 — RubroResolver + GymratTagger + orchestrator cleanup (DONE)
- `ar.scraper.aggregator.normalize.RubroResolver` — moved the
  `rubro`/`catEsTextil`/`catEsSuppl` selection block from `normalizarProducto`
  into `resolver(sitioKey, cat, rubroExistente)`. Uses `SiteClassification`
  + `CategoryGroups`.
- `ar.scraper.aggregator.normalize.GymratTagger` — moved `esGymrat` +
  `KW_TRAINING_ROPA` usage into `esGymrat(nombre, sitioKey, cat, rubro, marca)`,
  with its own small `anyMatch` copy (same pragmatic per-class-copy pattern
  as `CategoryClassifier`'s, not a taxonomy-drift risk).
- `NormalizerService` now takes a real 8-arg constructor (`PackQuantityDetector`,
  `CategoryClassifier`, `BrandExtractor`, `GenderResolver`, `SizeNormalizer`,
  `SubcategoryResolver`, `RubroResolver`, `GymratTagger`) and
  `normalizarProducto` is pure orchestration — sequences the 8 collaborators
  and rebuilds `Product` in the one place it always has. Deviation from the
  design's "12 collaborators" figure: the 4 static-only data/predicate
  holders (`GarmentTaxonomy`, `CategoryGroups`, `SiteClassification`,
  `NonTextileGuard`) are consumed via static import/reference inside their
  respective collaborators — exactly the Work Unit 3 precedent — not
  instance-injected into `NormalizerService`, which only calls
  `SiteClassification.sitioKey`/`SITIOS_PREMIUM` directly via static import.
  Injecting them as unused instance fields would have broken that
  established convention for no behavioral gain.
- RISK MITIGATION (task 8.4): added test-only
  `ar.scraper.aggregator.NormalizerServiceTestFactory.create()` (src/test/java)
  that news up all 8 collaborators and returns a wired `NormalizerService` —
  not a production facade. `NormalizerServiceTest` now calls
  `NormalizerServiceTestFactory.create()` instead of `new NormalizerService()`.
- Confirmed `ResultAggregator` (3 `normalizar(List<Product>)` call sites) and
  `ApiController` (no direct `NormalizerService` reference — Spring wires it
  only into `ResultAggregator`) need zero changes; `ResultAggregatorMetricsTest`
  mocks the type (`mock(NormalizerService.class)`), unaffected by the
  constructor change.

### Slice 9 (final) — ResultAggregator Decomposition + FacetCalculator Extraction (DONE)
- `ar.scraper.aggregator.FacetCalculator` — literal move of `calcularFacets` +
  `sortTalles` as a pure, stateless, non-`@Component` static utility (design
  explicitly calls it a "pure static utility", not a bean, matching the
  original method's own static modifier). Returns `ResultAggregator.Facets`.
- `ResultAggregator.calcularFacets(List<Product>)` kept as a thin **public
  static delegate** to `FacetCalculator.calcular(...)` — design-vs-actual-code
  deviation: the design/tasks text says "move" without explicitly discussing
  external callers, but `ResultAggregator.calcularFacets` is called directly
  (not via `ApiController`/`ScraperService` production code, which never
  reference it) by ~10 test files outside the `aggregator` package
  (`ApiControllerFinanciacionTest`, `ApiControllerMejoresInfantilVetoTest`,
  `ApiControllerMarcaMultiSelectTest`, `ApiControllerPrecioRangeTest`,
  `ApiControllerMejoresPackUnitPriceTest`, `ScraperServiceFinanciacionTest`)
  as a convenience fixture-builder for `AggregatedResult`. Deleting the method
  would have forced updating all of those call sites for zero behavioral
  gain — the same "consumer blast radius near zero" rationale ADR-2 applies
  to `NormalizerService`/`GroupingService` orchestrators surviving as thin
  entry points. This is a genuine external contract, not a permanent
  test-only-forwarding facade (ADR-2's "no permanent facades" targets
  package-private methods that existed ONLY for internal direct test calls,
  e.g. `extraerMarca`/`clasificar` — not a public static method with real
  external callers). `FacetCalculatorTest` (new, 6 focused unit tests, zero
  mocks) covers the pure function directly.
- `ResultAggregator.agregar()` decomposed into 5 named private methods in the
  EXACT original linear order, matching the tasks' mandated Spanish method
  names: `validarYContar` (per-site valid/invalid filtering + stats/errores),
  `deduplicarYOrdenar` (dedup by sitio+nombre, sort by precio asc),
  `ejecutarPipelineMl` (Java normalization + Python ML pipeline execution +
  ML enrichment), `persistirCategoriasRefinadas` (pre-ML vs post-ML categoria
  diff + DB persistence of refined categories, ADR-3's ordering-sensitive
  step), `enriquecerSenalYFinanciacion` (buy-signal then financing-signal
  enrichment). Two private records (`ValidationResult`, `MlPipelineResult`)
  carry each step's multi-value output between the named methods since Java
  lacks tuples — kept package-invisible (private, nested in
  `ResultAggregator`), not new injected collaborators, per ADR-3 ("do NOT
  introduce new injected collaborators" — the constructor stays 6-arg).
  `agregar()` itself is now pure sequencing/assembly: calls the 5 named steps
  in order, then does the DB persistence side-effects
  (`upsertProductos`/`guardarMlOutput`/`guardarCategoriaStats`), facets/min/
  max-price computation, logging, and background-training kickoff inline —
  mirroring the `NormalizerService.normalizarProducto` precedent of keeping
  final assembly in the one orchestrating method rather than hiding it behind
  another collaborator boundary.
- RISK MITIGATION (task 9.3, category-diff ordering): confirmed
  `persistirCategoriasRefinadas(normalizados, enriquecidos)` takes exactly
  the two params the design/ADR-3 specifies (no `mlOut` param), snapshots
  `catOriginal` from `normalizados` (pre-ML), and diffs against `enriquecidos`
  (post-ML) — `Product`'s record immutability guarantees `normalizados` still
  holds pre-ML categorias after ML enrichment returns a new list. Added two
  new approval tests to `ResultAggregatorMetricsTest`
  (`categoriaChangedByMl_persistedAndCountedInLastCatRefinadas`,
  `categoriaUnchangedByMl_notPersistedAndNotCounted`) exercising this exact
  snapshot-diff path end-to-end through `agregar()`, plus two more approval
  tests for the previously-untested dedup/sort step
  (`duplicateSiteAndNormalizedNombre_keepsOnlyFirstOccurrence`,
  `multipleValidProducts_sortedByPriceAscending`) — all four were written
  and confirmed green against the CURRENT (pre-decomposition) linear
  `agregar()` BEFORE the method split, per Strict TDD's Approval Testing
  discipline, then re-confirmed green after the split.
- Task 9.4: `ResultAggregatorMetricsTest` (original 3 tests + 4 new approval
  tests = 7 total) passes unmodified in constructor shape — 6-arg
  `ResultAggregator` constructor unchanged (ADR-3), `mock(NormalizerService.class)`
  still valid.
- Task 9.5: swept `ar.scraper.aggregator` (20 files: `FacetCalculator` +
  `ResultAggregator` at root, `NormalizerService` + 12 `normalize`
  collaborators, `GroupingService` + 3 `grouping` collaborators + `text.AccentStripper`)
  — matches the design's full package inventory exactly, no dead code or
  unused imports found (`mvn compile` with no `-Xlint` warnings).
- Full `mvn test` suite green: 460/460 tests, 0 failures, 0 errors (includes
  the new `FacetCalculatorTest` 6/6 and `ResultAggregatorMetricsTest` 7/7).

## Migration COMPLETE

All 9 slices done. `ar.scraper.aggregator` is now fully modularized per the
SDD design: `normalize` (12 collaborators + orchestrator), `grouping` (3
collaborators + orchestrator + promoted `ProductGroup`), `text`
(`AccentStripper`), root (`ResultAggregator` + `FacetCalculator`). Zero
observable behavior change end-to-end; `Product` record shape,
`ml_pipeline.py` JSON contract, and SQLite schema untouched throughout.
Ready for final branch review (`git diff --stat master...refactor/aggregator-solid-modularization`)
and PR to `master` under the accepted `size:exception`.
