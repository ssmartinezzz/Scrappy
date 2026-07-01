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
- [ ] Slice 7: SubcategoryResolver
- [ ] Slice 8: RubroResolver + GymratTagger + orchestrator cleanup + NormalizerServiceTestFactory
- [ ] Slice 9 (final): ResultAggregator decomposition + FacetCalculator extraction

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

## Remaining slices (Batch 2+)

- Slice 7: `SubcategoryResolver`.
- Slice 8: `RubroResolver` + `GymratTagger` + orchestrator cleanup +
  `NormalizerServiceTestFactory` (test-only wiring, not a production facade).
- Slice 9 (final): `ResultAggregator.agregar()` named-method decomposition +
  `FacetCalculator` extraction; final checkoff; open the single PR to
  `master`.
