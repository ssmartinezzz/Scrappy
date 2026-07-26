# Spec: llm-catalog-nlp

## Capability: llm-agent-chat (New)

### Purpose
Provider-pluggable, tool-using chat agent that lets a user inspect and correct catalog classification (category/gender/brand/subcategory) for real `productos` rows via natural-language conversation, grounded in the project's real taxonomy, without running concurrently with a scrape.

### Requirement: Chat Endpoint Contract
The system MUST expose `POST /api/agent/chat` accepting a domain-level request (conversation history + optional current product context) and returning the assistant's reply plus any tool calls executed, independent of the underlying LLM provider's wire format.

#### Scenario: Successful chat turn
- GIVEN the agent is available (no scrape running)
- WHEN a client POSTs a user message to `/api/agent/chat`
- THEN the response contains the assistant's final natural-language reply
- AND the response includes a record of any tools invoked during the turn

#### Scenario: Malformed request
- GIVEN a POST to `/api/agent/chat` with an empty or missing message
- WHEN the request is processed
- THEN the system MUST return a 4xx response without invoking the provider

### Requirement: Runtime Model Selection
The system MUST expose `GET /api/agent/models`, which discovers the set of currently available models from the active provider (e.g. the local Ollama instance) dynamically — not from a hardcoded list — and returns that set together with the configured default (`LLM_MODEL`). This endpoint MUST be read-only and MUST NOT be subject to the scrape-concurrency gate (unlike `/api/agent/chat`).

`POST /api/agent/chat` MUST accept an optional `model` field. When present and available, it overrides the env default `LLM_MODEL` for that request only; when absent, the env default is used. `LLM_MODEL` remains the default and a per-request override MUST NOT create any mutable server-side state (no persisted "current model" beyond the single request).

#### Scenario: Newly pulled model appears without config change
- GIVEN the user pulls a new model into the local Ollama instance
- WHEN `GET /api/agent/models` is subsequently called
- THEN the response MUST include the newly pulled model in the available set, with no code or env change required

#### Scenario: Models endpoint returns the configured default
- GIVEN `LLM_MODEL` is set to a given value
- WHEN `GET /api/agent/models` is called
- THEN the response MUST include that value as the default, alongside the discovered available set

#### Scenario: Models endpoint not gated by scrape status
- GIVEN `ScraperService` status is `RUNNING`
- WHEN a client calls `GET /api/agent/models`
- THEN the system MUST respond normally (no scrape-concurrency rejection), unlike `/api/agent/chat`

#### Scenario: Chat request overrides default model
- GIVEN `GET /api/agent/models` currently lists a model that is not the configured default
- WHEN a client POSTs to `/api/agent/chat` with `model` set to that available model
- THEN the system MUST use that model for the request instead of `LLM_MODEL`

#### Scenario: Chat request omits model field
- GIVEN a client POSTs to `/api/agent/chat` without a `model` field
- WHEN the request is processed
- THEN the system MUST use the env default `LLM_MODEL`

#### Scenario: Chat request specifies unknown model
- GIVEN a client POSTs to `/api/agent/chat` with a `model` value not present in the available set
- WHEN the request is processed
- THEN the system MUST return a 400 error naming the invalid model
- AND MUST NOT silently fall back to a different model

### Requirement: ChatProvider Domain Seam
The system MUST define a `ChatProvider` abstraction expressed in domain types (message, tool definition, tool call, tool result) with zero OpenAI- or Anthropic-specific shapes leaking into callers, so a future provider adapter can be added without changing the endpoint, tools, or loop.

#### Scenario: Adapter swap requires no caller change
- GIVEN the tool-use loop depends only on `ChatProvider`
- WHEN a different `ChatProvider` implementation is registered via configuration
- THEN the endpoint, tools, and loop code MUST require no changes to function with the new implementation

### Requirement: OpenAI-Compatible Adapter
The system MUST provide `OpenAiCompatProvider`, an adapter implementing `ChatProvider` against the OpenAI `chat/completions` wire protocol, working against both a local Ollama endpoint and any remote OpenAI-compatible endpoint.

#### Scenario: Local Ollama call
- GIVEN `LLM_PROVIDER=openai-compat` and `LLM_BASE_URL` pointing at a local Ollama instance
- WHEN the loop sends a chat turn with tool definitions
- THEN the adapter MUST translate domain messages/tools into the OpenAI wire shape and parse the response back into domain types

#### Scenario: Thinking disabled for qwen3
- GIVEN the configured model is a "thinking" model (e.g. `qwen3:14b`) served via Ollama
- WHEN the adapter builds the request
- THEN it MUST disable the model's thinking/reasoning mode so the reply returns without a hidden reasoning phase

### Requirement: Catalog Tools Grounded in Real Taxonomy
The system MUST expose exactly three tools to the model — search products, view product, reclassify product — and MUST inject the project's real taxonomy values (as defined by `GarmentTaxonomy`/`CategoryGroups`) into the tool schema or system context so the model cannot invent nonexistent categories, brands, or subcategories.

#### Scenario: Search tool returns real matches
- GIVEN a user asks about a product by name or partial name
- WHEN the model invokes the search tool
- THEN the tool MUST query the actual `productos` table and return matching rows (not fabricated data)

#### Scenario: View tool returns current classification
- GIVEN a product URL/id known to the agent
- WHEN the model invokes the view tool
- THEN the tool MUST return that product's current category, subcategory, gender, and brand as stored in `productos`

### Requirement: Tool Argument Validation (Safeguard A)
The system MUST validate tool call arguments at the tool boundary before executing any tool logic — product URL/id existence, and category/subcategory/brand membership in the real taxonomy (`GarmentTaxonomy`/`CategoryGroups`) — and MUST return a structured `is_error` tool result (never an uncaught exception or HTTP 500) when validation fails, so the model can self-correct within the loop. This safeguard catches malformed/invalid calls only; it does NOT validate whether a well-formed call is semantically correct.

#### Scenario: Invalid taxonomy value rejected
- GIVEN the model attempts to reclassify a product using a category value not present in `GarmentTaxonomy`/`CategoryGroups`
- WHEN the reclassify tool is invoked
- THEN the tool MUST return an `is_error` tool result listing valid taxonomy values, without writing to `productos`

#### Scenario: Invalid product reference rejected
- GIVEN the model invokes view or reclassify with a URL/id that does not exist in `productos`
- WHEN the tool is invoked
- THEN the tool MUST return an `is_error` tool result, without writing to `productos`

#### Scenario: Loop continues after a tool error
- GIVEN a tool call returns an `is_error` result
- WHEN the loop receives it
- THEN the loop MUST feed the error back to the model as a tool result and continue (not crash, not return an HTTP 500)

### Requirement: Reclassify Tool Produces a Proposal, Never Writes Autonomously (Safeguard B)
The `reclassify` tool MUST NOT write to `productos` when invoked by the model, regardless of how well-formed or confident the call is. It MUST instead return a proposal object — product identity, current classification value(s), and proposed new value(s) as a diff/preview — leaving the model's autonomous tool-use loop entirely read-only with respect to writes. This safeguard catches well-formed but semantically wrong calls, which schema/enum validation (Safeguard A) cannot catch.

#### Scenario: Reclassify call returns a proposal, no write occurs
- GIVEN a valid reclassification call (existing product, valid taxonomy value)
- WHEN the reclassify tool is invoked by the model
- THEN the tool MUST return a proposal (current → proposed value) as its tool result
- AND `productos` MUST remain unchanged after this call

### Requirement: Human-in-the-Loop Write Confirmation
The system MUST commit a proposed reclassification to `productos` ONLY after an explicit user confirmation action, taken outside the agent's autonomous tool-use loop. On confirmation, the write MUST go through the existing normalize write path (`DatabaseService.actualizarNormalizacion`) — no new/parallel write path is introduced. On rejection, no write MUST occur and the product MUST remain unchanged.

#### Scenario: User confirms — write commits
- GIVEN the agent has returned a reclassification proposal for a product
- WHEN the user takes the explicit confirm action
- THEN the system MUST call `DatabaseService.actualizarNormalizacion` (or equivalent existing normalize write path) to persist the proposed value
- AND the change MUST be visible on a subsequent view/search call

#### Scenario: User rejects — no write
- GIVEN the agent has returned a reclassification proposal for a product
- WHEN the user takes the explicit reject action
- THEN the system MUST NOT write to `productos`
- AND the product's classification MUST remain exactly as it was before the proposal

### Requirement: Tool-Use Loop
The system MUST run an iterative tool-use loop: send conversation + tool defs to the provider, execute any requested tool calls, feed results back, and repeat until the provider returns a final natural-language answer or a bounded iteration/error limit is hit.

#### Scenario: Multi-step canonical flow (search → view → propose → user confirms → write)
- GIVEN a user says "you classified La Remera SAD Adidas as Zapatilla running, that's wrong"
- WHEN the loop runs
- THEN the model MUST first call search, then view, then reclassify (in that dependency order)
- AND the reclassify call MUST return a proposal (current → proposed category) without writing to `productos`
- AND the assistant's reply MUST present that proposal to the user and await explicit confirmation
- AND the write MUST commit via the existing normalize write path ONLY after the user confirms in the UI (outside the autonomous loop)

#### Scenario: User directs a broader scope
- GIVEN a user explicitly asks the agent to review more than one product (e.g. "check the whole `Zapatilla running` category")
- WHEN the loop runs
- THEN the system MUST allow the agent to call the tools repeatedly across that user-directed scope
- AND the system MUST NOT impose a hard cap of one product per conversation

#### Scenario: Loop terminates on runaway iteration
- GIVEN the provider keeps requesting tool calls without producing a final answer
- WHEN a bounded iteration limit is reached
- THEN the loop MUST stop and return an error/partial-result response instead of looping indefinitely

### Requirement: Scrape-Concurrency Gate
The system MUST NOT allow an agent chat turn that invokes tools to execute while a scrape is in progress (`ScraperService` status `RUNNING`), to avoid GPU VRAM contention between the local LLM and the Marqo-FashionSigLIP visual model.

#### Scenario: Chat rejected during active scrape
- GIVEN `ScraperService` status is `RUNNING`
- WHEN a client POSTs to `/api/agent/chat`
- THEN the system MUST reject or defer the request with a clear "scrape in progress" response, without invoking the LLM provider

#### Scenario: Chat allowed once scrape finishes
- GIVEN `ScraperService` status transitions from `RUNNING` to `DONE`/`ERROR`
- WHEN a client subsequently POSTs to `/api/agent/chat`
- THEN the system MUST process the request normally

### Requirement: Env-Driven Provider Configuration
The system MUST configure the provider, model, base URL, and optional API key exclusively via environment variables (`LLM_PROVIDER`, `LLM_MODEL`, `LLM_BASE_URL`, `LLM_API_KEY`), consistent with the project's env-only + `RequiredEnvVarsGuard` fail-fast convention. `LLM_API_KEY` MUST be optional (not required for local Ollama use).

#### Scenario: Missing required var fails fast at startup
- GIVEN `LLM_PROVIDER` or `LLM_MODEL` is unset in the active (non-dev) profile
- WHEN the application starts
- THEN startup MUST abort with a message naming the missing variable(s), consistent with existing `RequiredEnvVarsGuard` behavior

#### Scenario: Local provider without API key
- GIVEN `LLM_PROVIDER=openai-compat`, `LLM_BASE_URL` set to a local Ollama address, and `LLM_API_KEY` unset
- WHEN the application starts and the agent is used
- THEN startup MUST succeed and chat calls MUST work without an API key

## Capability: agent-chat-ui (New)

### Requirement: Ask Agent Entry Point
The frontend MUST provide an "Ask Agent" button on a product view, positioned as a sibling to the existing reinforce/refuerzo button, that opens a chat panel scoped to that product.

#### Scenario: Opening the chat pre-fills product context
- GIVEN a user is viewing a product
- WHEN they click "Ask Agent"
- THEN a chat panel MUST open with that product's identity available as context for the first message

### Requirement: Chat Panel Behavior
The chat panel MUST send user messages to `/api/agent/chat`, render the assistant's reply, and surface tool activity (e.g. "searched", "viewed", "proposed a reclassification") distinctly from free-text reply content.

#### Scenario: Proposal rendered with confirm/reject affordance
- GIVEN the agent's reclassify tool returns a proposal (current → proposed classification)
- WHEN the chat panel renders that turn
- THEN the panel MUST display the proposed diff (current value vs. proposed value) with an explicit confirm ("Sí") and reject ("No") affordance
- AND no write MUST have occurred yet

#### Scenario: User confirms proposal in UI
- GIVEN a rendered proposal with confirm/reject affordance
- WHEN the user clicks confirm ("Sí")
- THEN the frontend MUST trigger the commit of that specific proposal
- AND the chat panel MUST then show the classification changed (old value → new value)

#### Scenario: User rejects proposal in UI
- GIVEN a rendered proposal with confirm/reject affordance
- WHEN the user clicks reject ("No")
- THEN the frontend MUST discard the proposal without committing any write
- AND the chat panel MUST reflect that the product's classification is unchanged

#### Scenario: Scrape-in-progress feedback
- GIVEN the backend rejects a chat call because a scrape is running
- WHEN the frontend receives that response
- THEN the chat panel MUST display a clear message telling the user to wait until the scrape finishes, instead of a generic error

### Requirement: Model Selector in Chat Panel
The chat panel MUST include a model `<select>` populated from `GET /api/agent/models`, preselected to the returned default, whose chosen value MUST be sent as the `model` field on the next `/api/agent/chat` request.

#### Scenario: Selector populated from available models
- GIVEN the chat panel opens
- WHEN it fetches `GET /api/agent/models`
- THEN the selector MUST list the returned available models
- AND the selector MUST be preselected to the returned default

#### Scenario: Selected model applies to next chat request
- GIVEN the user changes the selector to a non-default available model
- WHEN the user sends the next chat message
- THEN the frontend MUST include that model in the `/api/agent/chat` request payload
