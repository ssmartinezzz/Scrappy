package ar.scraper.web;

import ar.scraper.aggregator.normalize.CategoryGroups;
import ar.scraper.aggregator.normalize.RubroResolver;
import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.agent.AgentChatResponse;
import ar.scraper.agent.AgentConfig;
import ar.scraper.agent.CatalogAgentService;
import ar.scraper.agent.ConversationTurn;
import ar.scraper.agent.ProposeReclassifyTool;
import ar.scraper.agent.ProviderUnavailableException;
import ar.scraper.agent.ReclassifyProposal;
import ar.scraper.agent.Role;
import ar.scraper.agent.ToolStep;
import ar.scraper.agent.ViewProductTool;
import ar.scraper.identity.ActorResolver;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM Catalog Agent (llm-catalog-nlp) — chat / apply / models, grouped
 * together behind the same future admin-only gate. NOTE (task 5.7, scope
 * id 734): this whole group is the intended insertion point for an
 * admin-only auth guard once user accounts/roles exist — no no-op guard
 * is added now, this comment only marks WHERE it goes.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 */
class AgentEndpoints {

    /**
     * Transport caps on a client-supplied tool trace — see {@link #parseAgentTrace}.
     * {@code AgentChatPanel} carries the same numbers, but that copy is only a
     * convenience for what the browser stores: these are the enforced ones,
     * since any caller can post to this endpoint directly.
     */
    private static final int AGENT_MAX_TRACE_STEPS = 8;
    private static final int AGENT_MAX_TRACE_CALLS_PER_STEP = 6;
    private static final int AGENT_MAX_TRACE_ARG_KEYS = 8;
    private static final int AGENT_MAX_TRACE_ARG_LEN = 500;
    /**
     * How many raw entries the parser will even look at. Separate from the caps
     * above because those only count entries it ACCEPTS — without this, a body
     * made entirely of junk would still be walked end to end. Loose enough that
     * a few malformed entries before the valid ones cost nothing.
     */
    private static final int AGENT_MAX_TRACE_SCAN = 64;
    private static final ObjectMapper AGENT_MAPPER = new ObjectMapper();

    private final ScraperService service;
    private final ar.scraper.db.DatabaseService db;
    private final CatalogAgentService catalogAgentService;
    private final AgentConfig agentConfig;
    private final ActorResolver actorResolver;

    AgentEndpoints(ScraperService service,
                   ar.scraper.db.DatabaseService db,
                   CatalogAgentService catalogAgentService,
                   AgentConfig agentConfig,
                   ActorResolver actorResolver) {
        this.service = service;
        this.db = db;
        this.catalogAgentService = catalogAgentService;
        this.agentConfig = agentConfig;
        this.actorResolver = actorResolver;
    }

    // Not Spring-managed (built on demand, same rationale as
    // DatabaseService.rubroResolver, manual-classification-lock Phase 3): a
    // pure function of (sitioKey, categoria, rubroPrevio), so computing it here
    // for the in-memory patch is guaranteed to match what
    // aplicarReclasificacionAuditada persisted. Built lazily, not in the
    // constructor, so a test path that never reaches the reclassify branch
    // never touches `db` (close-1nf-and-3nf-foundation extension) —
    // several existing tests assert verifyNoInteractions(db) for exactly
    // that reason.
    //
    // NOT a hot path, so NOT cached: this method has exactly one call site,
    // reached at most once per POST /api/agent/apply request — a human-gated
    // write of a single product, never a per-product loop over the catalog
    // (unlike NormalizerService.normalizarProducto, which resolves the
    // Spring-managed RubroResolver singleton once and reuses it across the
    // whole scrape). The allocation itself is a single field assignment
    // wrapping the already-loaded SiteRegistry singleton — no I/O, no query.
    private RubroResolver rubroResolver() {
        return new RubroResolver(db.siteRegistry());
    }

    ResponseEntity<Object> agentChat(Map<String, Object> body) {
        if (service.getStatus() == ScraperService.ScraperStatus.RUNNING) {
            return ResponseEntity.status(409)
                    .body(Map.of("mensaje", "Hay un scraping en curso. Esperá a que termine."));
        }
        if (catalogAgentService == null) {
            return ResponseEntity.internalServerError().body(Map.of("mensaje", "Agent no disponible."));
        }

        Object messagesRaw = body.get("messages");
        List<ConversationTurn> conversation = new ArrayList<>();
        if (messagesRaw instanceof List<?> messagesList) {
            for (Object m : messagesList) {
                if (!(m instanceof Map<?, ?> mm)) continue;
                Object textRaw = mm.get("text");
                String text = textRaw == null ? "" : textRaw.toString();
                if (text.isBlank()) continue;
                Role role = parseAgentRole(mm.get("role"));
                // Only an assistant turn can carry tool activity, and only the
                // calls — results are re-executed server-side (agent-chat-continuity).
                List<ToolStep> trace = role == Role.ASSISTANT
                        ? parseAgentTrace(mm.get("trace"))
                        : List.of();
                conversation.add(new ConversationTurn(role, text, trace));
            }
        }
        if (conversation.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("mensaje", "El campo 'messages' es requerido y no puede estar vacío."));
        }

        Object modelRaw = body.get("model");
        String model = null;
        if (modelRaw != null && !modelRaw.toString().isBlank()) {
            model = modelRaw.toString();
            if (!catalogAgentService.listModels().contains(model)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("mensaje", "Modelo desconocido: '" + model + "'."));
            }
        }

        try {
            AgentChatResponse resp = catalogAgentService.run(conversation, model);
            return ResponseEntity.ok(resp);
        } catch (ProviderUnavailableException e) {
            return ResponseEntity.status(502)
                    .body(Map.of("mensaje", "No se pudo contactar al proveedor LLM.",
                            "codigo", "proveedor_no_disponible"));
        }
    }

    ResponseEntity<Object> agentModels() {
        // NOT scrape-gated (spec "Runtime Model Selection" / design D5) —
        // read-only metadata, touches no model/VRAM.
        if (catalogAgentService == null || agentConfig == null) {
            return ResponseEntity.internalServerError().body(Map.of("mensaje", "Agent no disponible."));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("available", catalogAgentService.listModels());
        resp.put("default", agentConfig.model());
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<Object> agentApply(ReclassifyProposal body) {
        if (service.getStatus() == ScraperService.ScraperStatus.RUNNING) {
            return ResponseEntity.status(409)
                    .body(Map.of("ok", false, "mensaje", "Hay un scraping en curso. Esperá a que termine."));
        }

        // agent-chat-finetune WU4: typed @RequestBody instead of Map<String,Object>
        // — the endpoint now reads the SAME field names ReclassifyProposal
        // actually carries (categoriaPropuesta, not "categoria"), fixing the
        // contract mismatch that 400'd every real confirm click. Per-field
        // required check names only what's actually missing (never a
        // blanket "'url' y 'categoria'" message when only one is absent).
        List<String> faltantes = new ArrayList<>();
        if (body.url() == null || body.url().isBlank()) faltantes.add("url");
        if (body.categoriaPropuesta() == null || body.categoriaPropuesta().isBlank()) faltantes.add("categoriaPropuesta");
        if (!faltantes.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "mensaje",
                    "Faltan campos requeridos: " + String.join(", ", faltantes) + "."));
        }
        if (!CategoryGroups.canonicalCategories().contains(body.categoriaPropuesta())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "mensaje", "Categoría inválida: '" + body.categoriaPropuesta() + "'."));
        }
        // normalize-db-schema-fks-1nf A.3: genero gets the same treatment
        // categoria already got, because THIS is the write path — the
        // ProposeReclassifyTool check is on the proposal, and this endpoint is
        // reachable without it. Blank/null is skipped deliberately: below,
        // a blank genero means "don't override", falling back to previo.genero()
        // — it is not a value being written, so validating it as one would
        // reject a legitimate no-op. Without this, an out-of-domain value
        // violates V6's chk_productos_genero_domain and the caller gets an
        // opaque 500 instead of a 400 naming what was wrong.
        String generoPropuesto = body.generoPropuesto();
        if (generoPropuesto != null && !generoPropuesto.isBlank()
                && !ProposeReclassifyTool.VALID_GENEROS.contains(generoPropuesto)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "mensaje", "Género inválido: '" + generoPropuesto + "'."));
        }

        // Server-side re-validation — the client is NEVER trusted to have
        // validated (design D4 Phase 2): look up the current product to
        // confirm the url exists in the in-memory catalog snapshot.
        Product current = ViewProductTool.find(service.getLastResult(), body.url());
        if (current == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "mensaje", "No existe ningún producto con esa url en el catálogo actual."));
        }

        // Staleness guard (agent-chat-finetune WU5): reads the DATABASE, never
        // `current` above — `current` and the proposal's own categoriaActual
        // both derive from the SAME in-memory snapshot (service.getLastResult()),
        // so comparing them against each other would never detect drift.
        // categoria is the only field the proposal carries a baseline for
        // (subCategoria/marca/genero have no "before" in ReclassifyProposal).
        // Fails closed: obtenerProducto returns Optional.empty() for both
        // "not found" and an actual read error (see its Javadoc) — either way
        // this is treated as a conflict, never as "safe to write".
        Optional<Product> dbProducto = db.obtenerProducto(body.url());
        String categoriaEnDb = dbProducto.map(Product::categoria).map(String::trim).orElse(null);
        String categoriaActualPropuesta = body.categoriaActual() != null ? body.categoriaActual().trim() : "";
        if (categoriaEnDb == null || !categoriaEnDb.equals(categoriaActualPropuesta)) {
            return conflictoStale(dbProducto);
        }
        Product previo = dbProducto.get();

        String subCategoria = body.subCategoriaPropuesta();
        String marca = body.marcaPropuesta();
        String genero = body.generoPropuesto();

        // agent-chat-finetune WU3: aplicarReclasificacionAuditada is the
        // truthful write path (WU1) — its boolean return is ALWAYS checked, so
        // a failed/no-op write can never be reported as "Reclasificación
        // aplicada." (the original silent-success defect this fixes). talles
        // and blank-field fallbacks now source from `previo` (the DB read
        // above), not from the in-memory `current`.
        // manual-classification-lock Phase 7: the acting identity is resolved
        // through the ONE ActorResolver seam (architecture/session-readiness,
        // obs #773) — never read inline. No role/permission check is performed
        // on it; it is recorded, not verified.
        String actor = actorResolver.current();
        boolean applied = db.aplicarReclasificacionAuditada(
                body.url(),
                body.categoriaPropuesta(),
                (marca != null && !marca.isBlank()) ? marca : previo.marca(),
                (genero != null && !genero.isBlank()) ? genero : previo.genero(),
                previo.talles(),
                (subCategoria != null && !subCategoria.isBlank()) ? subCategoria : previo.subCategoria(),
                previo,
                actor);

        if (!applied) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "mensaje", "No se pudo aplicar la reclasificación."));
        }

        // El catálogo se sirve de lastResult, no de la DB en cada request, así que
        // sin este parche la reclasificación recién persistida no se vería en
        // /api/data ni /api/mejores hasta el próximo scrape/restart. Mismo patrón
        // que eliminarProductoDeMemoria tras un soft-delete. Va DESPUÉS del check
        // de `applied`: nunca se parchea memoria por una escritura que no ocurrió.
        // rubro se deriva vía RubroResolver (mismo cómputo puro que ya usó
        // aplicarReclasificacionAuditada para persistirlo, design D3) — fix del
        // bug preexistente donde rubro quedaba stale tras un apply.
        String sitioKey = SiteClassification.sitioKey(previo.sitio());
        String rubro = rubroResolver().resolver(sitioKey, body.categoriaPropuesta(), previo.rubro());
        service.actualizarProductoEnMemoria(
                body.url(),
                body.categoriaPropuesta(),
                (marca != null && !marca.isBlank()) ? marca : previo.marca(),
                (genero != null && !genero.isBlank()) ? genero : previo.genero(),
                (subCategoria != null && !subCategoria.isBlank()) ? subCategoria : previo.subCategoria(),
                rubro);

        return ResponseEntity.ok(Map.of("ok", true, "applied", 1, "mensaje", "Reclasificación aplicada."));
    }

    /**
     * 422 response for the WU5 staleness guard — {@code actual} carries every
     * field {@link #agentApply} read from the DB (when available) so the UI
     * can show the caller what the product actually looks like now, without
     * requiring a second round-trip.
     */
    private ResponseEntity<Object> conflictoStale(Optional<Product> dbProducto) {
        Map<String, Object> actual = new LinkedHashMap<>();
        dbProducto.ifPresent(p -> {
            actual.put("categoria", p.categoria() != null ? p.categoria() : "");
            actual.put("marca", p.marca() != null ? p.marca() : "");
            actual.put("genero", p.genero() != null ? p.genero() : "");
            actual.put("subCategoria", p.subCategoria() != null ? p.subCategoria() : "");
        });
        return ResponseEntity.status(422).body(Map.of(
                "ok", false,
                "codigo", "conflicto_stale",
                "mensaje", "El producto cambió desde que se generó esta propuesta — volvé a consultar.",
                "actual", actual));
    }

    /**
     * A client may only ever author the two roles it actually speaks in.
     * Anything else — including a literal {@code "system"} or {@code "tool"} —
     * degrades to {@code USER}: the system prompt and every tool result are
     * server-authored, and a browser must not be able to smuggle one in by
     * naming a role.
     */
    private static Role parseAgentRole(Object roleRaw) {
        return roleRaw != null && "assistant".equalsIgnoreCase(roleRaw.toString())
                ? Role.ASSISTANT
                : Role.USER;
    }

    /**
     * Parses a past assistant turn's tool trace (agent-chat-continuity) out of
     * the untrusted request body, rebuilt field by field rather than bound —
     * the same posture {@code AgentChatPanel}'s {@code sanitizeSnapshot} takes
     * on the client side.
     *
     * <p>Shape validation only: a name that no tool answers to is dropped
     * later, by {@link ar.scraper.agent.CatalogAgentService} against its own
     * registry, which is the component that actually knows the tool set. The
     * caps here bound how much replay work one request can ask for — in
     * entries scanned, entries accepted, and payload size per accepted call —
     * and the service applies its own {@code MAX_REPLAY_CALLS} budget on top.</p>
     */
    private static List<ToolStep> parseAgentTrace(Object traceRaw) {
        if (!(traceRaw instanceof List<?> steps)) return List.of();
        List<ToolStep> parsed = new ArrayList<>();
        int scanned = 0;
        for (Object stepRaw : steps) {
            if (parsed.size() >= AGENT_MAX_TRACE_STEPS || ++scanned > AGENT_MAX_TRACE_SCAN) break;
            if (!(stepRaw instanceof Map<?, ?> stepMap)) continue;
            if (!(stepMap.get("calls") instanceof List<?> callsRaw)) continue;
            List<ToolStep.Call> calls = new ArrayList<>();
            int scannedCalls = 0;
            for (Object callRaw : callsRaw) {
                if (calls.size() >= AGENT_MAX_TRACE_CALLS_PER_STEP
                        || ++scannedCalls > AGENT_MAX_TRACE_SCAN) break;
                if (!(callRaw instanceof Map<?, ?> callMap)) continue;
                Object nameRaw = callMap.get("name");
                if (nameRaw == null || nameRaw.toString().isBlank()) continue;
                JsonNode args = callMap.get("arguments") instanceof Map<?, ?> argsMap
                        ? sanitizeAgentArgs(argsMap)
                        : AGENT_MAPPER.createObjectNode();
                calls.add(new ToolStep.Call(nameRaw.toString(), args));
            }
            if (!calls.isEmpty()) parsed.add(new ToolStep(calls));
        }
        return parsed;
    }

    /**
     * Rebuilds a replayed call's arguments as the flat object of scalars the
     * three catalog tools actually declare ({@code url} / {@code query} /
     * {@code categoria} / {@code limit} …), dropping nested, null and
     * over-long values instead of passing the client's map through.
     *
     * <p>Without this, {@code MAX_REPLAY_CALLS} bounds only HOW MANY calls get
     * replayed, not how big each one is — and every replayed call's arguments
     * are re-serialised into the model's context on every later turn of the
     * conversation.</p>
     */
    private static JsonNode sanitizeAgentArgs(Map<?, ?> raw) {
        ObjectNode clean = AGENT_MAPPER.createObjectNode();
        int scanned = 0;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            // Both bounds are needed: the key cap counts only entries ACCEPTED,
            // and a non-scalar value is accepted by none of the branches below,
            // so without the scan bound a map of nested junk is walked whole.
            if (clean.size() >= AGENT_MAX_TRACE_ARG_KEYS || ++scanned > AGENT_MAX_TRACE_SCAN) break;
            // The KEY is bounded too, not just the value — it is re-serialised
            // into the model's context on every later turn exactly like the
            // value is, so leaving it unbounded would defeat the whole cap.
            String key = truncateAgentArg(String.valueOf(entry.getKey()));
            if (key.isBlank()) continue;
            Object value = entry.getValue();
            if (value instanceof String s) {
                clean.put(key, truncateAgentArg(s));
            } else if (value instanceof Integer i) {
                clean.put(key, i);
            } else if (value instanceof Long l) {
                clean.put(key, l);
            } else if (value instanceof Number n) {
                clean.put(key, n.doubleValue());
            } else if (value instanceof Boolean b) {
                clean.put(key, b);
            }
        }
        return clean;
    }

    private static String truncateAgentArg(String s) {
        return s.length() > AGENT_MAX_TRACE_ARG_LEN ? s.substring(0, AGENT_MAX_TRACE_ARG_LEN) : s;
    }
}
