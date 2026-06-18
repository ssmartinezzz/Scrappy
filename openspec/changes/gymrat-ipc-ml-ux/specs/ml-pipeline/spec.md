# ML Pipeline Specification — gymrat-ipc-ml-ux

## Purpose

Specifies changes to the GYMRAT classification keyword set in `NormalizerService.java`.
No changes to scoring models, price percentile logic, or Python ML pipeline output schema.

## Requirements

### Requirement: Expanded GYMRAT Keyword Vocabulary

`KW_TRAINING_ROPA` in `NormalizerService.java` MUST include the full Argentine fitness
vocabulary below, in addition to any previously defined terms.
The guard conditions (no footwear, no supplements) MUST remain unchanged.

**Garment terms**: musculosa de gym, remera de gym, short deportivo, short de gym,
calza de gym, top deportivo, buzo de entrenamiento, pantalon deportivo.

**Activity terms**: gym, gimnasio, entrenamiento, functional, funcional, halterofilia,
hiit, cardio, aerobic, spinning, pilates, yoga.

**Style/tech terms**: dry-fit, dri-fit, compression, compresion, performance, active,
sport, athletic.

**Phrase terms**: para entrenar, de entrenamiento, para el gym, uso deportivo.

#### Scenario: Garment-specific keyword matches

- GIVEN a product named "Musculosa De Gym Nike Dri-Fit"
- WHEN `esGymrat()` is evaluated
- THEN it returns `true`

#### Scenario: Activity keyword matches

- GIVEN a product named "Remera Spinning Mujer"
- WHEN `esGymrat()` is evaluated
- THEN it returns `true`

#### Scenario: Footwear guard remains enforced

- GIVEN a product in category "Zapatillas" with name "Zapatillas Training Gym"
- WHEN `esGymrat()` is evaluated
- THEN it returns `false` (footwear guard blocks classification)

#### Scenario: Supplement guard remains enforced

- GIVEN a product in rubro "suplementos" with name "Proteina Gym"
- WHEN `esGymrat()` is evaluated
- THEN it returns `false` (supplement guard blocks classification)

---

### Requirement: GYMRAT Sub-Label Derivation

The system MUST derive a gym sub-label for frontend consumption by combining
the product's canonical `categoria` with the suffix " Gym" when `gymrat=true`.
This derivation MUST occur client-side in the frontend (no new Java field required).
The backend MUST serialize `gymrat: true/false` and `categoria` in `/api/data` responses.

#### Scenario: Sub-label derivable from existing fields

- GIVEN a product with `gymrat=true` and `categoria="Remera"`
- WHEN the frontend receives the product
- THEN the client derives sub-label "Remera Gym" from `categoria + " Gym"`

---

### Requirement: GYM_SITIOS Set Unchanged

The `GYM_SITIOS` set (`bulks`, `fuark`) MUST remain unchanged.
Site-based GYMRAT tagging MUST continue to work independently of keyword matching.

#### Scenario: Site-tagged gym product

- GIVEN a product from site "bulks" with no gym keywords in the name
- WHEN `esGymrat()` is evaluated
- THEN it returns `true` due to site membership in `GYM_SITIOS`
