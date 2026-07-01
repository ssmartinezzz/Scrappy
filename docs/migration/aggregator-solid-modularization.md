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
- [ ] Slice 2: Grouping package split (ProductGroup, ProductIdentity, JaccardSimilarity, AccentStripper)
- [ ] Slice 3: Data/predicate holders (GarmentTaxonomy, CategoryGroups, SiteClassification, NonTextileGuard)
- [ ] Slice 4: PackQuantityDetector (literal cut-paste)
- [ ] Slice 5: CategoryClassifier
- [ ] Slice 6: BrandExtractor + GenderResolver + SizeNormalizer
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

### Slice 2 — Grouping Package Split (pending)
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

### Slice 3 — Shared Data/Predicate Holders (pending)
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

## Remaining slices (Batch 2+)

- Slice 4: `PackQuantityDetector` literal cut-paste from
  `detectarCantidadUnidades` + patterns; diff-against-original before
  removing the old method.
- Slice 5: `CategoryClassifier` (clasificar + context guards + torso/piernas
  block matchers).
- Slice 6: `BrandExtractor` + `GenderResolver` + `SizeNormalizer`.
- Slice 7: `SubcategoryResolver`.
- Slice 8: `RubroResolver` + `GymratTagger` + orchestrator cleanup +
  `NormalizerServiceTestFactory` (test-only wiring, not a production facade).
- Slice 9 (final): `ResultAggregator.agregar()` named-method decomposition +
  `FacetCalculator` extraction; final checkoff; open the single PR to
  `master`.
