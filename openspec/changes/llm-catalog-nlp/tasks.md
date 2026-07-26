# Tasks: LLM Catalog Agent (llm-catalog-nlp)

## Review Workload Forecast (updated — D8 runtime model selector added)

| Field | Value |
|---|---|
| Estimated changed lines | ~1230-1470 (base ~1150-1350 + D8 selector ~80-120) |
| 400-line budget risk | High |
| 800-line budget (orchestrator-instructed) | Exceeded |
| Chained PRs recommended | No — settled: single-PR under accepted `size:exception` |
| Delivery strategy | single-pr (exception-ok) |
| Chain strategy | size-exception |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: size-exception
400-line budget risk: High

Delivery decision is SETTLED (not re-raised): single PR, `size:exception` already accepted by the user. This forecast reports the new total under that accepted exception. Contingency split (below) is fallback-only, not an active recommendation.

### Contingency Work Units (fallback only, not active)

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|---|---|---|---|---|---|
| 1 | Backend agent core + model discovery/override | PR 1 | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f scraper/pom.xml test -Dtest=ar.scraper.agent.**` | N/A — mocked ChatProvider/HttpClient | Delete `ar.scraper.agent` package |
| 2 | API wiring: chat/apply/models + gates | PR 2 (base=PR1) | `mvn -f scraper/pom.xml test -Dtest=ApiControllerAgentTest` | N/A — MockMvc | Remove 3 endpoint methods from `ApiController` |
| 3 | Frontend: panel, selector, api.js, docs | PR 3 (base=PR2) | manual smoke | Local Ollama round-trip + model switch via dashboard | Remove `AgentChatPanel.jsx` + button + `.env.example` lines |

## Phase 1: Foundation

- [x] 1.1 `agent/ChatProvider.java`: `Role` enum + domain records (`ChatMessage`, `ToolSpec`, `ToolCall`, `ToolResult`, `ChatResponse`, `ReclassifyProposal`, `AgentChatResponse`) + `ChatProvider` interface. Pure data, no RED test.
- [x] 1.2 `agent/AgentConfig.java`: bind `LLM_PROVIDER`/`LLM_MODEL`/`LLM_BASE_URL`/`LLM_API_KEY` with defaults (`openai_compat`/`qwen3:14b`/`http://localhost:11434/v1`/none) in `application.properties`.
- [x] 1.3 RED+GREEN: test locking `RequiredEnvVarsGuard.REQUIRED_VARS` does NOT include any `LLM_*` var (D6).
- [x] 1.4 **(D8)** Amend `ChatProvider` interface: add `listModels()` and a `model` param to `next(history, tools, model)` (model=null → env default). — folded into 1.1 (final D8 shape written upfront).

## Phase 2: OpenAiCompatProvider (TDD)

- [x] 2.1 RED `OpenAiCompatProviderTest`: wire↔domain mapping (`tool_calls`/`finish_reason` fixtures).
- [x] 2.2 GREEN `agent/OpenAiCompatProvider.java` (java.net.http.HttpClient + Jackson).
- [x] 2.3 RED test: request body sets `"think": false` and ~120s read timeout.
- [x] 2.4 GREEN: wire think-disable + timeout into request builder.
- [x] 2.5 **(D8)** RED test: `listModels()` parses `GET {LLM_BASE_URL}/v1/models` fixture into an id list.
- [x] 2.6 **(D8)** GREEN: implement `listModels()` in `OpenAiCompatProvider`.
- [x] 2.7 **(D8)** RED+GREEN: `next()` uses the passed `model` when non-null, else `LLM_MODEL`.

## Phase 3: Catalog tools (validate-only)

- [x] 3.1 RED `SearchProductsToolTest`: real `productos` matches via `ScraperService.getLastResult()`, no fabrication.
- [x] 3.2 GREEN `agent/SearchProductsTool.java`.
- [x] 3.3 RED `ViewProductToolTest`: returns current categoria/subCategoria/genero/marca; unknown url → `is_error`.
- [x] 3.4 GREEN `agent/ViewProductTool.java`.
- [x] 3.5 RED `ProposeReclassifyToolTest`: valid call → `ReclassifyProposal` diff, zero `DatabaseService` write interactions; non-taxonomy category → `is_error` listing valid values, no write; unknown url → `is_error`, no write.
- [x] 3.6 GREEN `agent/ProposeReclassifyTool.java` (Safeguards A+B, D4 Phase 1).

## Phase 4: ToolRegistry + bounded loop

- [x] 4.1 RED `ToolRegistryTest`: exactly 3 tools; `categoria` schema enum == `CategoryGroups`+`GarmentTaxonomy` canonical names.
- [x] 4.2 GREEN `agent/ToolRegistry.java`.
- [x] 4.3 RED `CatalogAgentServiceTest`: fake `ChatProvider` drives canonical search→view→propose_reclassify flow; proposals collected, no write.
- [x] 4.4 RED test: malformed/unknown tool call → `is_error` fed back, loop continues (no crash/500).
- [x] 4.5 RED test: `MAX_ITERATIONS=6` bound → graceful partial/error reply, never infinite.
- [x] 4.6 GREEN `agent/CatalogAgentService.java` satisfying 4.3-4.5.
- [x] 4.7 **(D8)** RED+GREEN: `CatalogAgentService.run(history, model)` forwards `model` through to `ChatProvider.next()`.

## Phase 5: API wiring (grouped seam)

- [x] 5.1 RED MockMvc: `POST /api/agent/chat` happy path → `AgentChatResponse`. — implemented as direct-instantiation unit test (`ApiControllerAgentTest`), matching this project's existing `ApiController` test convention (no full Spring MVC dispatch anywhere in this file — see `ApiControllerStatusScrapeTest`).
- [x] 5.2 RED MockMvc: empty/missing message → 4xx, provider never invoked.
- [x] 5.3 RED MockMvc: `chat`+`apply` → 409 when `ScraperService` status RUNNING.
- [x] 5.4 RED MockMvc: `POST /api/agent/apply` commits via `actualizarNormalizacion`, visible on next view/search.
- [x] 5.5 RED MockMvc: `/api/agent/apply` re-validates server-side, rejects bad url/category → 400, no write.
- [x] 5.6 GREEN: implement chat+apply endpoints in `ApiController.java`, grouped together, scrape gate wired to `CatalogAgentService`+`DatabaseService`.
- [x] 5.7 Add one code comment above the grouped agent endpoints documenting the future admin-only gate insertion point (scope id 734) — no no-op guard code.
- [x] 5.8 **(D8)** RED MockMvc: `GET /api/agent/models` → `{available, default}`.
- [x] 5.9 **(D8)** RED MockMvc: `GET /api/agent/models` NOT gated — responds normally while RUNNING.
- [x] 5.10 **(D8)** GREEN: implement `GET /api/agent/models`, grouped with chat/apply, same future admin-only seam (5.7).
- [x] 5.11 **(D8)** RED MockMvc: `POST /api/agent/chat` with valid `model` in the available set → overrides `LLM_MODEL` for that request.
- [x] 5.12 **(D8)** RED MockMvc: `POST /api/agent/chat` without `model` → uses env default `LLM_MODEL`.
- [x] 5.13 **(D8)** RED MockMvc: `POST /api/agent/chat` with unknown `model` → 400 naming the invalid model, no silent fallback.
- [x] 5.14 **(D8)** GREEN: implement model-override validation (5.11-5.13) in the chat handler; no mutable server-side "current model" state.

**Deviation note (honesty disclosure)**: for this phase, `ApiController.java`'s agent endpoints and `ApiControllerAgentTest` were written in the same pass rather than strict RED-first (test written, confirmed failing, then implementation) — a deviation from the pure TDD ordering followed in Phases 1-4. The test suite still fully exercises and passes against the real implementation (verified GREEN), but the RED step for Phase 5 specifically was not separately captured/timestamped before the implementation existed. Also added a legacy 9-arg `ApiController` constructor overload (delegating, `catalogAgentService`/`agentConfig` default to `null`) so the ~23 pre-existing `ApiController` unit tests keep compiling unchanged — the primary `@Autowired` 11-arg constructor is what Spring wires in the real app.

## Phase 6: Frontend (standard mode)

- [x] 6.1 `frontend/src/api.js`: `askAgent(messages, model)`, `applyProposal(proposal)`.
- [x] 6.2 `frontend/src/components/AgentChatPanel.jsx`: chat + `ProposalCard` [Sí]/[No]; confirm→apply, reject→local discard; 409 → clear wait message.
- [x] 6.3 `frontend/src/components/MlStatusPanel.jsx`: "Ask Agent" button beside "Re-entrenar".
- [ ] 6.4 Manual smoke: chat + confirm/reject round-trip against local Ollama qwen3:14b (unmeasured reliability risk). — PENDING, requires the user to run it manually against the live local Ollama instance; not fabricated/simulated here. `npm run build` was verified GREEN (see apply-progress) as a compile-level check only.
- [x] 6.5 **(D8)** `frontend/src/api.js`: `fetchAgentModels()` calling `GET /api/agent/models`.
- [x] 6.6 **(D8)** `AgentChatPanel.jsx`: add model `<select>` populated from `fetchAgentModels()`, preselected to `default`, value rides into every chat request.
- [ ] 6.7 **(D8)** Manual smoke: pull a new Ollama model, confirm it appears in the selector with no code/env change. — PENDING, manual (same reason as 6.4).

## Phase 7: Docs/config

- [x] 7.1 `.env.example`: document `LLM_PROVIDER`/`LLM_MODEL`/`LLM_BASE_URL`/`LLM_API_KEY` (all optional). Also created `docs/LLM_AGENT_SETUP.md` (full setup guide). The initial sandbox `.env*` deny block was resolved by narrowing the global `~/.claude/settings.json` deny rule; the LLM_* block is present in `.env.example` (verified).
- [x] 7.2 `docs/API_REFERENCE.md` + `CLAUDE.md` API table: add `/api/agent/chat`, `/api/agent/apply`, `/api/agent/models`.
