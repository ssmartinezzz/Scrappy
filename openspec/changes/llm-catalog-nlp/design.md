# Design: LLM Catalog Agent (provider-pluggable, tool-using)

## Technical Approach
New self-contained package `ar.scraper.agent`. A domain `ChatProvider` seam speaks provider-agnostic records; `OpenAiCompatProvider` (ships now) owns Ollama `/v1/chat/completions` wire translation. A server-side `CatalogAgentService` runs the tool-use loop once, executing catalog tools against `DatabaseService`. `POST /api/agent/chat` in `ApiController`'s package. Follows proposal + scope; honors scope clarifications: provider = env-driven, local model default = `qwen3:14b` (not 4b) with a **runtime UI model selector within that provider (D8)**, reclassify reuses `actualizarNormalizacion` write path, use case is interactive user-directed correction. **The agent proposes; the user confirms — the agent loop holds NO write authority (two-phase propose/confirm, D4).** Additive, no DB migration.

## Architecture Decisions

### D1: ChatProvider seam at the domain, not the wire
**Choice**: `interface ChatProvider { ChatResponse next(List<ChatMessage>, List<ToolSpec>); List<String> listModels(); }` over domain records only. **Alternatives**: abstract at `base_url` (one OpenAI adapter, swap host). **Rationale**: Anthropic is a genuinely different protocol — `/v1/messages`, typed `content[]` blocks, `tool_use`/`tool_result`, loop on `stop_reason=="tool_use"` — vs OpenAI-compat `/v1/chat/completions`, `choices[]`, `tool_calls`/`role:tool`, `finish_reason`. A `base_url` swap cannot bridge that. Domain types (below) let both adapters translate wire→domain internally. Future `AnthropicProvider` uses official `com.anthropic.*` (`AnthropicOkHttpClient`) and drops in WITHOUT touching endpoint, tools, or loop. NOT built now.

### D2: HTTP client = `java.net.http.HttpClient`
**Choice**: JDK `HttpClient` + Jackson, matching project convention (`InflacionService`, `PythonRunner`, `VaypolPage` all use it). **Alternatives**: add Spring `RestClient`/WebClient/OkHttp. **Rationale**: no new dependency; consistent. Must send `"think": false` (qwen3 thinking gotcha — otherwise burns tokens and looks hung) and a generous read timeout (~120s) since 14b spills partly to CPU.

### D3: Bounded server-side tool-use loop, non-streaming
**Choice**: `CatalogAgentService` loops: `provider.next()` → if `toolCalls` non-empty, execute via `ToolRegistry`, append `ToolResult` messages, repeat; `MAX_ITERATIONS=6`; stop when no tool calls. Non-streaming (return full transcript). **Alternatives**: client-driven loop; SSE streaming. **Rationale**: loop lives once in the domain (seam requirement). Non-streaming keeps `ApiController` + adapter simple for v1. Bound prevents runaway. **All tools inside this loop are READ-ONLY (search/view/propose) — no tool commits a write (D4).** Malformed/unknown tool call → return an `is_error` `ToolResult` string so the model can self-correct within budget; budget exhaustion → graceful "could not complete" reply, never a 500.

### D4: Two-phase reclassify — agent PROPOSES, user CONFIRMS (supersedes direct write)
**Choice**: the agent NEVER holds write authority. Reclassification is split across two phases.

**Phase 1 — PROPOSE (inside the agent loop, read-only):** the `propose_reclassify` tool is a pure function. It VALIDATES (URL exists in the current catalog; `categoriaPropuesta ∈` the `CategoryGroups`/`GarmentTaxonomy` canonical enum) and RETURNS a proposal diff — it performs **no DB write**. Invalid args (unknown URL, non-taxonomy category) → `is_error` `ToolResult` fed back to the model (never 500). The turn ends with zero or more pending proposals surfaced in the chat response.

Proposal record:
```java
record ReclassifyProposal(String url, String nombreProducto,
    String categoriaActual, String categoriaPropuesta,
    String subCategoriaPropuesta, String marcaPropuesta, String generoPropuesto) {}
```

**Phase 2 — CONFIRM (outside the agent loop, user-initiated):** a separate endpoint `POST /api/agent/apply` receives the user-confirmed proposal(s) and commits each via `DatabaseService.actualizarNormalizacion(url, categoria, marca, genero, talles, subCategoria)`. It re-validates server-side (URL + taxonomy enum) before writing — the client is never trusted to have validated. Scrape-gated (409, D5) like every other write endpoint. The frontend renders each proposal as a card with [Sí]/[No]; confirm → `apply` call; reject → discard, nothing persists.

Tools table:
| Tool | Phase | Signature | Maps to |
|---|---|---|---|
| `search_products` | loop, read | `(query, limit=10)` | `ScraperService.getLastResult()` filter (name/brand contains) |
| `view_product` | loop, read | `(url)` | single `Product` lookup |
| `propose_reclassify` | loop, read | `(url, categoria, subCategoria?, marca?, genero?)` | validate only → `ReclassifyProposal` (NO write) |
| — apply — | out-of-loop | `POST /api/agent/apply` | `DatabaseService.actualizarNormalizacion(...)` |

`categoria` param schema `enum` is injected at registry build from `CategoryGroups` + `GarmentTaxonomy` canonical names, and the system prompt embeds the same vocabulary, so the model cannot invent categories (raw qwen3 returned fabric "frisa" as brand, generic "Ropa" as category).

**Alternatives**: reclassify writes inside the loop (original D4); a single endpoint auto-committing agent output. **Rationale / security win**: because the agent has NO write authority, the blast radius of unreliable tool-calling collapses to "a bad proposal the user can reject" — nothing persists without explicit human confirmation. This is the PRIMARY mitigation for the unmeasured qwen3:14b tool-calling reliability risk (a hallucinated category is a rejected card, not a corrupted catalog row).

### D5: Scrape-concurrency gate — reject with 409
**Choice**: `/api/agent/chat` and `/api/agent/apply` check `service.getStatus() == ScraperStatus.RUNNING` → HTTP 409 with a clear message; do NOT queue. `GET /api/agent/models` is NOT gated (cheap metadata, touches no model/VRAM). **Alternatives**: queue/defer; downgrade to a smaller model. **Rationale**: exact pattern already used by `/api/db/productos`, `/api/financiacion/**`. Gating (not a dumber model) is the scope-confirmed VRAM mitigation: no scrape → SigLIP unloaded → 14b owns all 10GB. Rejecting is honest and simple; the user retries after the scrape.

### D6: Env config — optional, provider-validated
`LLM_PROVIDER` (default `openai_compat`), `LLM_BASE_URL` (default `http://localhost:11434/v1`), `LLM_MODEL` (default `qwen3:14b`), `LLM_API_KEY` (optional). NOT added to `RequiredEnvVarsGuard.REQUIRED_VARS` — all have safe local defaults; local Ollama needs no key. Defaults in `application.properties`; the adapter fails fast at call time only if a remote provider needs a key that is absent. Consistent with env-only config. `LLM_MODEL` is the DEFAULT model; the runtime selector (D8) overrides it per-request without mutating any server state.

### D7: Frontend — button beside reinforce, chat panel with proposal cards + model selector
"Ask Agent" button sits next to the "Re-entrenar (texto + GPU)" button in `MlStatusPanel.jsx`. `AgentChatPanel.jsx` = message list + input calling `api.js askAgent(messages, model)` → `POST ${BASE}/api/agent/chat`. **A `<select>` (D8) at the top, populated from `GET /api/agent/models` with `default` preselected, rides its value into every chat request.** **Proposals returned in the chat response render as ProposalCards showing `categoriaActual → categoriaPropuesta` (+ product name) with [Sí]/[No]: [Sí] → `api.js applyProposal(proposal)` → `POST ${BASE}/api/agent/apply`, then mark the card applied; [No] → discard the card locally, no call.** CORS via `VITE_API_BASE_URL`.

### D8: Runtime model selector — dynamic discovery + per-request override, no mutable state
**Choice**: model is selectable from the UI at runtime WITHIN the active (env-driven) provider — provider itself stays env-config.
- **Seam**: `ChatProvider.listModels()` returns available model ids. `OpenAiCompatProvider.listModels()` queries `GET {LLM_BASE_URL}/v1/models` (OpenAI-compatible, protocol-consistent). Future `AnthropicProvider.listModels()` returns the Claude catalog (not built now).
- **Endpoint**: `GET /api/agent/models` → `{available:[...ids], default:"<LLM_MODEL>"}`. Read-only metadata, NOT scrape-gated (D5), but grouped with the other agent endpoints behind the same future admin-only seam.
- **Override**: `POST /api/agent/chat` gains an optional `model` field. Present AND in the available set → overrides `LLM_MODEL` for THAT request. Absent → env default. Present but unknown/unavailable → **400, clear error, NO silent fallback**. No server-side "current model" state — env sets default, request carries override (stateless, env-only-consistent).

**Alternatives**: mutable server-side "current model" set via a PUT; hardcoded model list. **Rationale**: concrete realization of "model is config, swap without code" — `ollama pull qwen2.5:7b` makes the model appear in the dropdown on next fetch, zero config change. Statelessness avoids a mutable global fighting the env-only philosophy and keeps concurrent requests independent.

## Data Flow
```
GET /api/agent/models → ChatProvider.listModels() ──HTTP──> Ollama GET /v1/models
   → {available[], default} → <select> in chat panel

MlStatusPanel[Ask Agent] → POST /api/agent/chat {messages, model?} → ApiController
  → scrape gate (RUNNING? → 409); model? validate ∈ available else 400
  → CatalogAgentService.run(history, model)   [READ-ONLY loop]
      loop≤6: ChatProvider.next() ──HTTP──> Ollama /v1/chat/completions (chosen model)
        toolCalls? → ToolRegistry → search/view/propose_reclassify (validate only)
        append ToolResult → repeat
  → { assistantText, proposals[] } → chat panel renders ProposalCards
        │
   user [Sí] ↓ (out of loop)
POST /api/agent/apply {proposal} → scrape gate → re-validate (URL+enum)
  → DatabaseService.actualizarNormalizacion(...)   [ONLY write, human-confirmed]
```

## Interfaces
```java
package ar.scraper.agent;
enum Role { SYSTEM, USER, ASSISTANT, TOOL }
record ChatMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId) {}
record ToolSpec(String name, String description, JsonNode paramsSchema) {}
record ToolCall(String id, String name, JsonNode arguments) {}
record ToolResult(String toolCallId, String content, boolean isError) {}
record ReclassifyProposal(String url, String nombreProducto, String categoriaActual,
    String categoriaPropuesta, String subCategoriaPropuesta,
    String marcaPropuesta, String generoPropuesto) {}
record AgentChatResponse(String assistantText, List<ReclassifyProposal> proposals) {}
record ChatResponse(String assistantText, List<ToolCall> toolCalls) { boolean done(){return toolCalls.isEmpty();} }
interface ChatProvider {
    ChatResponse next(List<ChatMessage> h, List<ToolSpec> tools, String model); // model=null → env default
    List<String> listModels();
}
interface CatalogTool { ToolSpec spec(); ToolResult execute(JsonNode args); }
```
`GET /api/agent/models` → `{ available:[String], default:String }` (not gated).
`POST /api/agent/chat` req `{ messages:[{role,text}], model? }` → resp `AgentChatResponse`; 409 if scraping; 400 if `model` unknown.
`POST /api/agent/apply` req `ReclassifyProposal` (or `{proposals:[...]}`) → resp `{ ok, applied:int, mensaje }`; 409 if scraping, 400 if URL/category invalid.

## File Changes
| File | Action | Description |
|---|---|---|
| `agent/ChatProvider.java` + domain records | Create | Seam (`next`+`listModels`) + domain types + `ReclassifyProposal` |
| `agent/OpenAiCompatProvider.java` | Create | Ollama wire adapter: chat + `GET /v1/models`, `think:false`, timeout |
| `agent/CatalogAgentService.java` | Create | Bounded READ-ONLY tool-use loop, per-request model, collects proposals |
| `agent/ToolRegistry.java` + 3 `*Tool.java` | Create | search/view/propose_reclassify, taxonomy enums |
| `agent/AgentConfig.java` | Create | `LLM_*` binding + provider selection |
| `web/ApiController.java` | Modify | `GET /api/agent/models` + `POST /api/agent/chat` (model override) + `POST /api/agent/apply` + gates |
| `frontend/src/components/AgentChatPanel.jsx` | Create | Chat UI + model `<select>` + ProposalCard [Sí]/[No] |
| `frontend/src/components/MlStatusPanel.jsx` | Modify | Ask Agent button |
| `frontend/src/api.js` | Modify | `fetchAgentModels()`, `askAgent(messages, model)`, `applyProposal()` |
| `.env.example` | Modify | Document `LLM_*` |

## Testing Strategy
| Layer | What | How |
|---|---|---|
| Unit | wire→domain mapping (`tool_calls`/`finish_reason`); `/v1/models` → id list | mocked JSON fixtures |
| Unit | loop bound; malformed tool-call → `is_error`, no 500 | fake `ChatProvider` |
| Unit | `propose_reclassify` validates + returns diff, writes NOTHING | mocked `DatabaseService`, assert no interaction |
| Unit | `propose_reclassify` rejects non-taxonomy category → `is_error` | taxonomy fixture |
| Integration | `/api/agent/models` returns available+default; not gated during RUNNING | MockMvc |
| Integration | `/api/agent/chat` model override: valid→used, unknown→400, absent→default | MockMvc + fake provider |
| Integration | `/api/agent/apply` re-validates + commits via `actualizarNormalizacion`; bad URL/category→400 | MockMvc |
| Integration | `chat`+`apply` 409 during RUNNING | MockMvc |
| Manual | qwen3:14b real tool-calling reliability | live Ollama (open risk) |

## Threat Matrix
Rows (git repo selection, commit/push state, PR commands, documentation-like paths) — **N/A**: no VCS/PR automation, no shell, no executable-file classification. Relevant agentic boundary (not in the git-centric matrix) handled by D3/D4/D8: the agent loop is read-only, so no autonomous write exists; the only write (`/api/agent/apply`) requires explicit human confirmation and re-validates URL + taxonomy enum server-side against a fixed-SQL write path (no dynamic query); the model override is validated against the discovered available set (unknown → 400, no injection of an arbitrary upstream target). Prompt-injection blast radius = a proposal card the user can reject; nothing persists un-confirmed.

## Migration / Rollout
No migration. Additive. Rollback = hide button + unregister `/api/agent/chat`, `/api/agent/apply`, `/api/agent/models`; package is self-contained.

## Open Questions
- [ ] qwen3:14b multi-step tool-calling reliability is UNMEASURED (only single-JSON classification was benchmarked). Impact is now bounded by the propose/confirm safeguard (bad proposals are rejectable, never persisted). If still too weak, the D8 selector lets the user pick another pulled local model at runtime, or `LLM_PROVIDER`/`LLM_BASE_URL` switch to Claude/OpenAI-compat with no code change; the future `AnthropicProvider` seam is the escape hatch.
