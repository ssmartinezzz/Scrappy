# pc-builder-agent-tool — phase 5 of the PC builder: the agent's `propose_pc` tool

**Created:** 2026-09-18 · **Branch:** `feat/pc-builder-agent-tool` — **PR #205** (stacked on `feat/saved-pcs-armadores`, #204 → #203 → #202 → #201 → #200 → `master`)
**Engram mirror:** `odd/pc-builder-agent-tool/tasks` (project `scrappy`)
**Phase 4:** [`saved-pcs-armadores.md`](./saved-pcs-armadores.md) — persistence + `/armadores`.

## Objective

Let the LLM catalog agent assemble a PC on request ("armame una PC de
$1.500.000 con GPU") through a fourth read-only tool, `propose_pc`, that runs
`PcBuilder.armar` over the live snapshot and returns the same JSON shape
`GET /api/pcs/builder` serves. The model narrates the picks; nothing is
written. Saving stays in `/pcs` (`POST /api/pcs/save`).

## Constraints

- `agent/` may not depend on `web/` (ArchUnit `agentNoDependeDeWeb`), and
  `PcBuilder` is hand-built in `ApiController`, not a bean → the tool builds
  its own `PcBuilder(RecommendationService)`.
- The build → JSON serialization currently lives in `web/PcsEndpoints`; it
  moves to `pcs/` so endpoint and tool share one shape (DOC-1 for code).
- Every tool validates at the boundary and returns `is_error`, never throws.
- Grounding: a successful `propose_pc` sets `grounded` (it carries real
  catalog data). `isEmptySearchResult` stays `search_products`-only.
- TDD: strict (session config). Runner: `JAVA_HOME=…/jdk-24 mvn -f scraper/pom.xml clean test`
  per CONTRIBUTING.md.

## Tasks

- [x] **T1** `pcs/PcBuildJson.toJson(PcBuild) → ObjectNode` extracted from
  `PcsEndpoints.builder`; endpoint delegates. Existing endpoint tests pass untouched.
- [x] **T2** `agent/ProposePcTool` (`propose_pc`): args `presupuesto`
  (number ≥ 0, optional, 0 = no cap), `conGpu` (boolean, default false),
  `excluir` (array of urls, optional). Errors: negative/non-numeric
  presupuesto, no snapshot. Ok: `PcBuildJson` of the build. Tests: happy path
  (picks + totalEstimado), conGpu adds a gpu slot, excluir skips a url,
  negative budget → is_error, no snapshot → is_error.
- [x] **T3** Register in `ToolRegistry` (4 tools, constructor order
  search/view/propose_reclassify/propose_pc); system prompt gains the
  "ARMAR una PC" capability + when to call it; `GROUNDING_NUDGE` lists the
  tool. `ToolRegistryTest` → 4 tools; `CatalogAgentServiceTest`: a turn whose
  only tool is `propose_pc` returns `COMPLETE`.
- [x] **T4** Docs: CLAUDE.md agent section (4 tools), `docs/LLM_EMBED.md`
  tool table + loop diagram; CLAUDE.md PC builder "Pendiente" line.

## Acceptance

Full backend suite green (`clean test`, grep `ERROR]`/`BUILD FAILURE`);
ArchUnit green; a manual chat turn against Ollama produces a build (best effort,
only if the provider is up).

## Progress

2026-09-18 — 4/4 done.

- T1 `pcs/PcBuildJson` + `PcBuildJsonTest`; `PcsEndpoints.builder` delegates.
- T2 `agent/ProposePcTool` + `ProposePcToolTest` (6: happy path, conGpu adds
  gpu slot, excluir skips a url (two CPUs — the builder re-admits the pool when
  every candidate is excluded), negative budget, non-numeric budget, no
  snapshot). RED observed as compile failure before the class existed.
- T3 `ToolRegistry` 4 tools; prompt + `GROUNDING_NUDGE`; `ToolRegistryTest`
  → 4; `CatalogAgentServiceTest.proposePcOnlyToolCallCompletes`.
- T4 CLAUDE.md (agent section + fase 5 pointer), `docs/LLM_EMBED.md`.

Verification: targeted 54/54 (ProposePcTool, ToolRegistry, CatalogAgentService,
PcBuildJson, BackendLayeringArchTest incl. `agentNoDependeDeWeb`) — re-run by
the parent; full backend suite 2195 run / 0 failures / 0 errors / 7 skipped
(pre-existing infra skips), BUILD SUCCESS, `clean` included (writer's run).
Not verified: a live chat turn against Ollama (provider not exercised).

Next: none planned for the agent; the watts floor → per-GPU estimate remains
the open item from phase 2.
