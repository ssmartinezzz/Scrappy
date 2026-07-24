# Proposal: LLM Catalog Agent (provider-pluggable, tool-using)

## Intent
Catalog data quality (category/gender/brand/subcategory) is auto-derived and sometimes wrong, with no interactive way to inspect and fix a single product. Give the user an **"Ask Agent"** button (sibling to the reinforce/refuerzo button) that opens a chat where an LLM can **see and act on real catalog data** via tools — search products, view a product, re-classify a product. A blank chatbot that cannot see `productos` was explicitly rejected. Provider is env-pluggable so the user runs a free local model today and can later point at a stronger cloud model without breaking their 10 GB GPU.

## Scope
### In Scope
- Domain seam `ChatProvider` speaking domain types (message / tool / tool-result over `productos`).
- `OpenAiCompatProvider` adapter (local Ollama `:11434/v1/chat/completions` + any OpenAI-compatible remote — same wire).
- Endpoint `POST /api/agent/chat` + the tool-use loop (lives once in the domain).
- Catalog tools: search products, view product, reclassify product — grounded in project taxonomy.
- Env-driven config (`LLM_PROVIDER` / `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`), consistent with env-only + `RequiredEnvVarsGuard`.
- Frontend "Ask Agent" button + chat UI.

### Out of Scope (follow-up)
- `AnthropicProvider` adapter (`/v1/messages`, `content[]` typed blocks, `tool_use`/`tool_result`, `stop_reason` loop — a genuinely different wire protocol, NOT OpenAI-compatible; this is exactly why the seam abstracts at the domain level, not at `base_url`). Must drop in later WITHOUT touching endpoint, tools, or loop.
- The separate-worktree CLI (a second consumer of the same seam — just don't preclude it).
- Full-catalog batch re-classification (throughput ~1.67 s/product → interactive per-product only).

## Capabilities
### New Capabilities
- `llm-agent-chat`: `ChatProvider` seam, OpenAI-compat adapter, `/api/agent/chat`, tool-use loop, catalog tools, env config.
- `agent-chat-ui`: "Ask Agent" button + chat panel wired to the endpoint.
### Modified Capabilities
- None at spec level (reclassify tool reuses existing normalize path).

## Approach
Interface at the domain, not the wire. `/api/agent/chat`, catalog tools, and the tool-use loop exist once; only wire translation is per-adapter. Tools inject the project's real taxonomy (`GarmentTaxonomy` / `CategoryGroups`) into the model so it grounds on real vocabulary instead of hallucinating (raw qwen3 returned fabric "frisa" as a brand and a discarded generic category). Local default a small coexisting model (e.g. `qwen3:4b`) with thinking disabled.

## Affected Areas
| Area | Impact | Description |
|------|--------|-------------|
| `scraper/.../web/` | New | `/api/agent/chat` controller |
| `scraper/.../ (new agent pkg)` | New | `ChatProvider` seam + `OpenAiCompatProvider` + tools + loop |
| `scraper/.../aggregator/normalize/` | Reuse | Taxonomy grounding + reclassify path |
| `scraper/.../config/` | Modified | Env vars via `RequiredEnvVarsGuard` |
| `frontend/` | New | "Ask Agent" button + chat UI |

## Risks
| Risk | Likelihood | Mitigation |
|------|------------|------------|
| VRAM contention (3080 10 GB; qwen3:14b fills it, spills to CPU; SigLIP needs same VRAM during scrape) | High | Default a small model (qwen3:4b) that coexists, or gate agent use so it never runs concurrently with a scrape |
| Zero-shot hallucination without grounding | High | Tools/prompt inject real taxonomy; constrained tool outputs |
| Thinking-model hang (qwen3 burns tokens reasoning) | Med | Disable think in adapter |
| Seam leaks OpenAI shape, blocking Anthropic later | Med | Domain types only; adapter owns wire translation |

## Rollback
Additive change. Remove/hide the "Ask Agent" button and unregister the `/api/agent/chat` route; new agent package is self-contained — no migrations, no changes to existing scrape/ML paths.

## Dependencies
- Running OpenAI-compatible endpoint (local Ollama or remote). Env vars set. No new DB tables.

## Success Criteria
- [ ] "Ask Agent" on a product opens a chat that calls catalog tools and returns grounded answers using real taxonomy.
- [ ] Agent can search, view, and reclassify a product end-to-end.
- [ ] Switching `LLM_PROVIDER`/`LLM_MODEL` between local and an OpenAI-compat remote requires no code change.
- [ ] Seam design admits a future `AnthropicProvider` without touching endpoint, tools, or loop.

## Proposal question round
Scope/architecture were confirmed by the user on 2026-07-24 (Engram sdd/llm-catalog-nlp/scope). No open product questions. Assumptions carried forward for review: (a) reclassify tool writes through the existing normalize path rather than a new write model; (b) local default `qwen3:4b` for GPU coexistence; (c) agent is interactive per-product only, not a batch re-classifier.
