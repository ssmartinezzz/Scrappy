# Agent autonomy — search, reclassify, hard guardrails

## Objective

The embedded LLM agent (`ar.scraper.agent`) answers product-existence questions and
reclassification requests on its own, without the user steering every step — while it can
NEVER touch users, roles or cron jobs.

## Problem

"¿Tenés algún SSD SATA de TB en descuento?" answered "No encontré productos", yet 12 active
SATA SSDs of 1–2 TB with `precio_orig > precio` exist (measured 2026-09-29 on the dev DB).

- `SearchProductsTool` matches `query` as ONE contiguous substring: "ssd sata" appears
  literally in 0 names (they read `HD SSD 1TB HIKSEMI WAVE SATA III`).
- No discount filter exists and `precio_orig` is never returned.
- One empty search is enough for the loop to deliver the canned "no matches" message; the
  model is never told to relax its criteria first.
- Reclassify: the system prompt forces search → view → propose, one product at a time.

## Decisions

- Reclassification stays human-confirmed (user choice 2026-09-29: "Proponer mejor, vos
  confirmás"). No tool writes to the DB; `POST /api/agent/apply` remains the only write path.
- Users/roles/cron are already unreachable today (all tools read-only, no DB reference). The
  change is to make that a structural guarantee, not a convention.

## Scope

Backend only, `scraper/src/main/java/ar/scraper/agent/` + its tests +
`BackendLayeringArchTest`. The frontend already renders N proposals per turn.

## TDD

Strict TDD ON (source: user global config). Runner:
`JAVA_HOME=/home/santiago/openjdk-24_linux-x64_bin/jdk-24 mvn -f scraper/pom.xml clean test -Djvm=/usr/lib/jvm/java-21-openjdk-amd64/bin/java`
(`-Dtest=...` during development; always `clean`).

## Tasks

- [x] **T1 — Token search.** `query` is split into tokens; every token must appear in
  `nombre` or `marca`, any order. A number glued or spaced to a unit matches both ways
  (`1tb` ≡ `1 tb`). Accent/case-insensitive as today.
- [x] **T2 — Discounts.** Results carry `precioOrig` and `descuentoPct`; new boolean param
  `enOferta` keeps only `precioOrig > precio`; with `enOferta` results are sorted by
  discount descending. Results also carry `subCategoria` so a reclassify needs no extra view.
- [x] **T3 — Retry before "no hay".** On an empty search the loop tells the model once (system
  message) to retry relaxing criteria (fewer words, `categoria` instead of `query`) before
  the canned no-matches reply is delivered. Tool args are logged at DEBUG.
- [x] **T4 — Autonomous proposals.** System prompt: propose directly when search already
  shows the current classification; several products may be proposed in one turn; still
  never writes.
- [x] **T5 — Guardrails.** ArchUnit: `ar.scraper.agent..` must not depend on
  `security..`, `identity..`, `scheduling..`, `db..`. `ToolRegistryTest` pins the closed
  tool set (exactly the 4 names) and asserts no tool name/description refers to
  users/roles/cron. System prompt states the refusal explicitly.

### Round 2 — robust relevance ranking (user request 2026-09-29)

"Pero que sea más robusta: filtro de palabras vacías y un buen algoritmo que asigne pesos."
Interpreted as relevance RANKING (BM25), not category classification.

Rationale: T1 requires every token, so a filler word ("ssd sata **de** tb") empties the result;
and results come in catalog order, not by relevance. `aggregator/grouping/StopWords` is NOT
reusable: it drops colours, sizes and genders, which are real search criteria.

- [x] **T6 — Query tokenizer.** New class in `ar.scraper.agent`: accent/case normalize, glue
  number+unit, split on non-alphanumerics, drop a search-specific stopword list (Spanish
  function words + conversational filler: "tenés", "hay", "algún", "busco", "quiero"…; never
  colours/sizes/genders/units), light plural stemming applied to query AND product text.
- [x] **T7 — BM25F ranker.** Fields nombre (1.0), marca (2.0), categoria+subCategoria (1.5);
  k1=1.2, b=0.75; IDF over the current snapshot, index cached per snapshot instance. Match
  levels per token: exact word 1.0, prefix (token ≥ 4 chars) 0.8, unit suffix after digits
  ("tb" ↔ "1tb") 0.8, typo (Damerau-Levenshtein ≤1 for len ≥5, ≤2 for len ≥8) 0.5. No raw
  substring matching (the space is the word boundary — see GOTCHAS).
- [x] **T8 — Strict then relaxed selection.** Strict: every content token matches. If strict
  is empty, relaxed: coverage ≥ 50%, each row flagged `coincidencia:"parcial"` +
  `terminosFaltantes`; the prompt tells the model to present partial matches as such. Order:
  score desc; with `enOferta`: discount desc, then score. Each row carries `relevancia`.
- [x] **T9 — Proof.** Unit tests for tokenizer/ranker; tool tests for "ssd sata de tb",
  plurals, typo, filler-only query → error (no content tokens), relevance order.

## Acceptance

- The SSD question, replayed as tool args (`query="ssd sata 1tb", enOferta=true`), returns the
  discounted SATA SSDs, biggest discount first.
- Full `clean test` green; ArchUnit rule red if an agent class imports `UsuarioRepository`.

## Progress

Created 2026-09-29. Implemented and verified 2026-09-29 (uncommitted).

- T1/T2 RED: `SearchProductsToolTest` 12 run, 5 failures + 1 error (contiguous-substring, no `enOferta`,
  no `precioOrig`/`descuentoPct`/`subCategoria`). GREEN: 12/12; `SearchProductsToolFiltersTest` 8/8 untouched.
  Includes the SSD acceptance test (`ssd sata 1 tb` + enOferta -> 1 result; `ssd sata` -> both discounted).
- T3/T4 RED: `CatalogAgentServiceTest` 5 failures (2 retry, 3 prompt). GREEN: 28/28, existing tests untouched.
- T5: `agentNoTocaUsuariosRolesNiCron` green on current code; negative control (temp agent class holding a
  `db.UsuarioRepository` field) turned it red, control file removed. `ToolRegistryTest.toolSetIsClosed...`
  is a characterization test (green on arrival; its first draft was red only because it wrongly banned
  the word "usuario" from descriptions, fixed in the test). Tool args logged at DEBUG in `ToolRegistry.execute`.
- Full suite (`clean test`): 2996 run, 0 failures, 0 errors, 7 skipped, BUILD SUCCESS.
- Pending: nothing in scope. Not yet run against a real LLM / booted backend.

### Round 2 evidence (2026-09-29, uncommitted)

- Files: `agent/QueryTokenizer.java`, `agent/RelevanceRanker.java` (new); `SearchProductsTool`, `CatalogAgentService`
  (prompt; `systemPrompt()` now package-private for testing). Tests: `QueryTokenizerTest` (10), `RelevanceRankerTest` (9),
  `SearchProductsToolTest` (+9), `CatalogAgentPromptPartialMatchTest` (1, new file: CatalogAgentServiceTest stays untouched).
- RED (stubs compiling, no behaviour): tokenizer 7/9 failing, ranker 8/9, tool 7 of 21; prompt test 1/1 (no "coincidencia" text).
  GREEN: 18/18 tokenizer+ranker; then tool suites 81/81 with `SearchProductsToolFiltersTest`, `CatalogAgentServiceTest`, `ToolRegistryTest` unedited.
- Mid-way failure found by the acceptance test: gluing "510 SATA" -> "sa510sata" hid the word "sata" in
  "WD BLUE SA510 SATA III". Fix: product text indexes loose words AND glued number+unit forms (query side only glued).
- Full suite (`clean test`): 3025 run, 0 failures, 0 errors, 7 skipped, BUILD SUCCESS.
- Perf, synthetic 16k catalog, JRE 21 via surefire: index build + first query ~300-500 ms; warm query ~8-13 ms (measured, printed by
  `perfSanityOn16kProducts`, not asserted).
- Decisions: strict rows carry no `coincidencia` flag (only partial ones do); with `enOferta=true` the words descuento/oferta/rebaja/
  rebajado/promo/promocion are dropped from the query (the parameter already says it); `j` added to the "-es" consonant set
  (relojes); typo matching only between purely alphabetic words (rtx3060 != rtx3070); no-query searches keep catalog order and
  carry no `relevancia`; query capped at 16 content terms.
- Deviations: none (no existing test edited).
- Pending: not exercised against a real LLM / booted backend.

### Real-catalog probe (orchestrator, 2026-09-29)

Scratch harness over the 22,200 active rows of the dev DB, calling `SearchProductsTool.execute`.
The user's own question now returns the discounted SATA SSDs; typo `kingstom` → Kingston; `rtx 5090`
(absent) → other RTX flagged `parcial`; index build ~415 ms, warm queries 10–30 ms.
Defect found and fixed (TDD, RED observed): under `enOferta` the raw discount fraction ordered by
site rounding noise (9.0907% vs 9.0912%) and pushed the best 1TB matches out of the top 6. Now sorted
by the rounded pct the user sees, ties by relevance. Test `enOfertaTiesOnRoundedPctBreakByRelevance`.
Full suite after the fix: 3026 run, 0 failures, 0 errors, 7 skipped.
Open: not yet exercised through Qwen on a booted backend. Near-duplicate rows (same product, several
sites) appear consecutively in results.

### Qwen probe (qwen3:14b, in-process, real catalog, 2026-09-29)

Harness: real `CatalogAgentService` + `OpenAiCompatProvider` → local Ollama + real tools over the
22,200 active rows. HTTP/auth not exercised (`/api/agent/**` is ADMIN; credentials not available).

- SSD question: correct call (`query="SSD SATA 1 TB", enOferta, categoria=Almacenamiento`), rows
  returned, but Qwen's prose ignored them and answered about motherboard compatibility. Delivered as COMPLETE.
- "zapatillas nike de hombre < 100 mil": Qwen used `categoria="Zapatilla"`; exact-equality excludes
  "Zapatilla Running/Urbana/…" → false "no encontré" (2 real matches exist). The retry nudge did not rescue it.
- RAM review: searched correctly, proposed nothing (all rows are RAM — plausible), answered with shopping advice.
- Users/roles/cron/admin requests: no action taken (safe); Qwen refused correctly but the grounding
  gate discarded the refusal and returned the generic "No pude responder eso…".

Proposed follow-ups (not authorized yet): A) `categoria` matches its family ("Zapatilla" ⊇ "Zapatilla *")
— reverses the documented exact-equality decision in SearchProductsTool; B) deterministic canned refusal
for users/roles/permissions/cron intents; C) prompt: answer by listing returned rows (name, site, price,
discount), no unrequested advice.

### Round 3 — fixes from the Qwen probe (authorized 2026-09-29: "implementá A, B y C")

- [x] **T10 (A) — Category family.** `categoria="Zapatilla"` matches "Zapatilla" and every
  "Zapatilla <x>" (word-boundary prefix: equal, or starts with value + " "); never a raw substring.
  Reverses the documented exact-equality decision (user-authorized); update that comment.
- [x] **T11 (B) — Deterministic refusal.** Requests to create/modify/delete users, roles, permissions,
  or to create/launch/modify cron jobs/scrapes get a fixed refusal BEFORE the provider is called,
  outcome `CAPABILITY` (existing wire value, no frontend/openapi change). Must not fire on product
  queries ("rol de cocina", "remera usuario"… keep precision; test both sides).
- [x] **T12 (C) — Answer from rows.** Prompt: after a search, answer by listing the returned rows
  (nombre, sitio, precio, descuento when present); no unrequested advice; for a review request, say
  explicitly how many were reviewed and that none needed changes when that is the case.

### Round 3 evidence (2026-09-29, uncommitted)

- T10: `SearchProductsTool.enFamilia` (equal ignoring case, or starts with value + " "); comment and tool description updated.
  Tests (+2 in `SearchProductsToolFiltersTest`): RED 2/2 (family rows missing), GREEN. No existing test asserted exact exclusion.
- T11: new `RestrictedIntents` (action word + restricted object within 3 tokens; scrape verbs alone unless preceded by "para"),
  checked first in `CatalogAgentService.run`, fixed Spanish refusal, `CAPABILITY`, no trace. Tests: `RestrictedIntentsTest` (28: 15
  positive, 12 negative, null) RED 15/28 (stub returning false); `CatalogAgentRestrictedTest` (provider and registry never touched)
  RED (NPE from unscripted provider mock). GREEN after implementation.
  Borderline: "¿cuántos usuarios tiene el catálogo?" is NOT restricted (a read, no action word; falls to the normal flow).
  Known limit: "dame un mouse para usuario zurdo"-style queries only stay safe because the object is more than 3 tokens away.
- T12: prompt additions (list rows with nombre/sitio/precio/descuentoPct, partial = closest, no unrequested advice, review states count and
  "none needs changes"; categoria described as family). `CatalogAgentPromptAnswerTest` (4) RED 4/4, GREEN.
- Full suite (`clean test`): 3061 run, 0 failures, 0 errors, 7 skipped, BUILD SUCCESS (+35 vs 3026).
- Pending: not exercised against Qwen/booted backend again; docs (`docs/LLM_EMBED.md`) not checked for the "exact category" wording.

### Qwen re-probe after Round 3 (2026-09-29)

Orchestrator re-ran the full suite: 3061 run, 0 failures, 0 errors, 7 skipped.
- T10 verified live: "zapatillas nike de hombre < 100 mil" now finds the 2 real rows (Run Defy, Killshot 2).
- T11 verified live: the 3 restricted requests return the fixed refusal, outcome CAPABILITY, 0 s, provider not called.
- T12 NOT effective. qwen3:14b: SSD → answered with questions, ignoring the rows; Nike → a
  reclassification verdict, in English; RAM → shopping advice. qwen2.5:7b lists rows but invents
  parameters (`marca`, `ddr`, `precioMax:200000`); the tool silently ignores unknown keys, so
  `marca:"Nike"` was dropped and DC/Puma/Adidas were presented as the answer.

Proposed (pending user): D) reject unknown arguments in search_products with an error naming the
valid ones (model self-corrects); E) render search answers server-side from the rows (deterministic),
with the model's prose not trusted for listing.

### Round 4 — D and E (authorized 2026-09-29: "implementá D y E")

- [x] **T13 (D) — Unknown arguments are errors.** `search_products` rejects any argument key not in its
  schema with an error naming the unknown key(s) and the valid ones, so the model self-corrects
  (`marca`→`query`, `ddr`→`query`). Nothing is silently dropped.
- [x] **T14 (E) — Server-rendered search answers.** When a turn's successful calls include no
  `propose_reclassify`/`propose_pc` and the last non-empty `search_products` result has rows, the
  delivered text is rendered server-side from those rows (markdown the chat already renders): a
  count line, then one line per row: linked nombre, sitio, price es-AR, and "−x% (antes $y)" when
  discounted; `parcial` rows introduced as closest matches with the missing terms. The model's prose
  is not delivered on those turns. Rows gain `sitio`. Proposal/PC turns keep model prose.

### Round 4 evidence (2026-09-29, uncommitted)

- T13: `SearchProductsTool` rejects keys outside its 8 declared ones before any filtering (error names the unknown keys, lists
  the valid ones, says brand/model/specs go in `query`). `SearchProductsToolUnknownArgsTest` (4): RED 3/4 (unknown keys ignored),
  GREEN 4/4. Replay: traces hold only calls that succeeded, so valid-key calls replay unchanged; an old stored trace that contains
  an invented key (succeeded before this change) now replays as an error tool message, which the registry already turns into a
  normal tool result — no crash, and nothing is re-recorded in the trace.
- T14: new `SearchAnswerRenderer` (count line / partial header with union of missing terms, `- [nombre](url) — sitio — $1.234`,
  `**−x%** (antes $y)`; brackets stripped from names, url `( ) space` percent-encoded). `SearchProductsTool` rows gain `sitio`.
  `CatalogAgentService` keeps the last non-empty search of THIS turn and renders it on done+grounded unless the turn had
  propose_reclassify/propose_pc; outcome COMPLETE and trace unchanged; discarded prose logged at DEBUG.
  Tests: `SearchAnswerRendererTest` (6) RED 5/6 (stub returned null), `CatalogAgentSearchAnswerTest` (7) RED 3/7 (the three
  search-only cases; the four keep-prose cases are characterizations, green by design). GREEN after implementation.
- Markdown support (frontend `lib/richText.jsx`): `- ` bullets, `**bold**`, `[text](url)` (http/https only, no `(` `)` in url), bare URLs.
- Deviation: one existing assertion edited, `CatalogAgentServiceTest.emptySearchGetsOneRelaxRetryAndCanRecover`:
  `isEqualTo("Encontré la Zapatilla SAD Adidas.")` -> `startsWith("Encontré 1 producto:")` (search-only turn now delivers the listing).
- Prompt: one sentence appended to the T12 paragraph (search-only turns get the listing shown automatically; do not repeat it). The
  T12 wording pinned by `CatalogAgentPromptAnswerTest` ("listando los productos devueltos") was left untouched, so it now reads
  slightly redundant for search-only turns but still applies to proposal/PC turns.
- Full suite (`clean test`): 3078 run, 0 failures, 0 errors, 7 skipped, BUILD SUCCESS.
- Pending: not re-probed with Qwen on a booted backend; `docs/LLM_EMBED.md` not updated.

### Qwen re-probe after Round 4 (2026-09-29)

Orchestrator re-ran the full suite: 3078 run, 0 failures, 0 errors, 7 skipped. The only edited assertion
belongs to a test added in this same feature (no committed test line removed).
- Listings are now truthful (rendered from rows) on both models. Nike: 2 real rows, linked, discount shown.
- qwen3:14b SSD: searched with `limit:1`, then called `propose_reclassify` 3× with the SAME no-op diff
  (categoria unchanged) → 3 identical proposal cards; a proposal turn keeps prose, so the user got a
  reclassification verdict instead of the SSD list.
- qwen2.5:7b invents filter VALUES (`precioMax:50000`) → pendrives for the SSD question; RAM review came
  back "faltan: ddr4" because the invented cap excluded every DDR4. The header is honest but does not
  show the filters that were applied, so the user cannot see why.

Proposed (pending user): F) `propose_reclassify` rejects a no-op diff and the service dedupes identical
proposals per turn; G) the rendered header states the filters applied (categoria, género, precio,
enOferta, excluir) so an invented constraint is visible.
