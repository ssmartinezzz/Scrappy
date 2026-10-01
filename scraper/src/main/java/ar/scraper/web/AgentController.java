package ar.scraper.web;

import ar.scraper.scrape.ScraperStatus;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteClassification;
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
import ar.scraper.security.ActorResolver;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.AgentDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/** LLM Catalog Agent: chat / apply / models. */
@RestController
@RequestMapping("/api")
public class AgentController {

    /** Enforced transport caps on a client-supplied tool trace ({@link #parseAgentTrace}); the frontend copy is a convenience. */
    private static final int AGENT_MAX_TRACE_STEPS = 8;
    private static final int AGENT_MAX_TRACE_CALLS_PER_STEP = 6;
    private static final int AGENT_MAX_TRACE_ARG_KEYS = 8;
    private static final int AGENT_MAX_TRACE_ARG_LEN = 500;
    /** Raw entries the parser will look at; the caps above only count ACCEPTED ones, so junk would otherwise be walked whole. */
    private static final int AGENT_MAX_TRACE_SCAN = 64;
    private static final ObjectMapper AGENT_MAPPER = new ObjectMapper();

    private final ScraperService service;
    private final RubroResolver rubroResolver;
    private final ar.scraper.catalog.ProductPort productos;
    private final CatalogAgentService catalogAgentService;
    private final AgentConfig agentConfig;
    private final ActorResolver actorResolver;

    public AgentController(ScraperService service,
                   RubroResolver rubroResolver,
                   ar.scraper.catalog.ProductPort productos,
                   CatalogAgentService catalogAgentService,
                   AgentConfig agentConfig,
                   ActorResolver actorResolver) {
        this.service = service;
        this.rubroResolver = rubroResolver;
        this.productos = productos;
        this.catalogAgentService = catalogAgentService;
        this.agentConfig = agentConfig;
        this.actorResolver = actorResolver;
    }

    @PostMapping("/agent/chat")
    public ResponseEntity<ApiResponse<AgentChatResponse>> agentChat(@RequestBody Map<String, Object> body) {
        if (service.getStatus() == ScraperStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "scrape_en_curso",
                    "Hay un scraping en curso. Esperá a que termine.");
        }
        if (catalogAgentService == null) {
            throw agenteNoDisponible();
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
                // Only an assistant turn carries tool activity, and only the calls: results are re-executed server-side.
                List<ToolStep> trace = role == Role.ASSISTANT
                        ? parseAgentTrace(mm.get("trace"))
                        : List.of();
                conversation.add(new ConversationTurn(role, text, trace));
            }
        }
        if (conversation.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "El campo 'messages' es requerido y no puede estar vacío.");
        }

        Object modelRaw = body.get("model");
        String model = null;
        if (modelRaw != null && !modelRaw.toString().isBlank()) {
            model = modelRaw.toString();
            if (!catalogAgentService.listModels().contains(model)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                        "Modelo desconocido: '" + model + "'.");
            }
        }

        try {
            AgentChatResponse resp = catalogAgentService.run(conversation, model);
            return ResponseEntity.ok(ApiResponse.ok(resp));
        } catch (ProviderUnavailableException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "proveedor_no_disponible",
                    "No se pudo contactar al proveedor LLM.");
        }
    }

    private static ApiException agenteNoDisponible() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "agente_no_disponible", "Agent no disponible.");
    }

    // Not scrape-gated: read-only metadata, touches no model/VRAM.
    @GetMapping("/agent/models")
    public ResponseEntity<ApiResponse<AgentDtos.Models>> agentModels() {
        if (catalogAgentService == null || agentConfig == null) {
            throw agenteNoDisponible();
        }
        return ResponseEntity.ok(ApiResponse.ok(
                new AgentDtos.Models(catalogAgentService.listModels(), agentConfig.model())));
    }

    @PostMapping("/agent/apply")
    public ResponseEntity<ApiResponse<AgentDtos.Applied>> agentApply(@RequestBody ReclassifyProposal body) {
        if (service.getStatus() == ScraperStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "scrape_en_curso",
                    "Hay un scraping en curso. Esperá a que termine.");
        }

        // Typed body: reads the same field names ReclassifyProposal carries (categoriaPropuesta, not
        // "categoria"). The per-field check names only what is actually missing.
        List<String> faltantes = new ArrayList<>();
        if (StringUtils.isBlank(body.url())) faltantes.add("url");
        if (StringUtils.isBlank(body.categoriaPropuesta())) faltantes.add("categoriaPropuesta");
        if (!faltantes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "Faltan campos requeridos: " + String.join(", ", faltantes) + ".");
        }
        if (!CategoryGroups.canonicalCategories().contains(body.categoriaPropuesta())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "Categoría inválida: '" + body.categoriaPropuesta() + "'.");
        }
        // genero is validated like categoria because THIS is the write path (reachable without
        // ProposeReclassifyTool). Blank is skipped on purpose: it means "keep previo.genero()", not a
        // value being written. Without this an out-of-domain value hits V6's CHECK and surfaces as a 500.
        String generoPropuesto = body.generoPropuesto();
        if (StringUtils.isNotBlank(generoPropuesto)
                && !ProposeReclassifyTool.VALID_GENEROS.contains(generoPropuesto)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "Género inválido: '" + generoPropuesto + "'.");
        }

        // The client is never trusted to have validated: confirm the url exists in the catalog snapshot.
        Product current = ViewProductTool.find(service.getLastResult(), body.url());
        if (current == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "No existe ningún producto con esa url en el catálogo actual.");
        }

        // Staleness guard: reads the DATABASE, never `current` — `current` and the proposal's
        // categoriaActual both derive from the same in-memory snapshot, so comparing them would never
        // detect drift. Fails closed: obtenerProducto returns empty for both "not found" and a read error.
        Optional<Product> dbProducto = productos.obtenerProducto(body.url());
        String categoriaEnDb = dbProducto.map(Product::categoria).map(String::trim).orElse(null);
        String categoriaActualPropuesta = body.categoriaActual() != null ? body.categoriaActual().trim() : "";
        if (categoriaEnDb == null || !categoriaEnDb.equals(categoriaActualPropuesta)) {
            throw conflictoStale(dbProducto);
        }
        Product previo = dbProducto.get();

        String subCategoria = body.subCategoriaPropuesta();
        String marca = body.marcaPropuesta();
        String genero = body.generoPropuesto();

        // aplicarReclasificacionAuditada is the truthful write path: its boolean is always checked so a
        // failed write is never reported as applied. Blank-field fallbacks come from `previo` (the DB read).
        // The acting identity goes through the ONE ActorResolver seam; it is recorded, not verified.
        String actor = actorResolver.current();
        boolean applied = productos.aplicarReclasificacionAuditada(
                body.url(),
                body.categoriaPropuesta(),
                (StringUtils.isNotBlank(marca)) ? marca : previo.marca(),
                (StringUtils.isNotBlank(genero)) ? genero : previo.genero(),
                previo.talles(),
                (StringUtils.isNotBlank(subCategoria)) ? subCategoria : previo.subCategoria(),
                previo,
                actor);

        if (!applied) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error_interno",
                    "No se pudo aplicar la reclasificación.");
        }

        // The catalog is served from lastResult, so without this patch the reclassification would not show
        // in /api/data or /api/mejores until the next scrape. AFTER the `applied` check: never patch memory
        // for a write that did not happen. rubro is derived via RubroResolver, the same pure computation
        // aplicarReclasificacionAuditada persisted.
        String sitioKey = SiteClassification.sitioKey(previo.sitio());
        String rubro = rubroResolver.resolver(sitioKey, body.categoriaPropuesta(), previo.rubro());
        service.actualizarProductoEnMemoria(
                body.url(),
                body.categoriaPropuesta(),
                (StringUtils.isNotBlank(marca)) ? marca : previo.marca(),
                (StringUtils.isNotBlank(genero)) ? genero : previo.genero(),
                (StringUtils.isNotBlank(subCategoria)) ? subCategoria : previo.subCategoria(),
                rubro);

        return ResponseEntity.ok(ApiResponse.ok(new AgentDtos.Applied(true, 1, "Reclasificación aplicada.")));
    }

    /** 422 for the staleness guard; {@code details} carries what the DB holds now so the UI needs no second round-trip. */
    private ApiException conflictoStale(Optional<Product> dbProducto) {
        Map<String, Object> actual = new LinkedHashMap<>();
        dbProducto.ifPresent(p -> {
            actual.put("categoria", p.categoria() != null ? p.categoria() : "");
            actual.put("marca", p.marca() != null ? p.marca() : "");
            actual.put("genero", p.genero() != null ? p.genero() : "");
            actual.put("subCategoria", p.subCategoria() != null ? p.subCategoria() : "");
        });
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "conflicto_stale",
                "El producto cambió desde que se generó esta propuesta — volvé a consultar.",
                Map.of("actual", actual));
    }

    /** A client may only author USER/ASSISTANT; "system"/"tool" degrade to USER since those are server-authored. */
    private static Role parseAgentRole(Object roleRaw) {
        return roleRaw != null && "assistant".equalsIgnoreCase(roleRaw.toString())
                ? Role.ASSISTANT
                : Role.USER;
    }

    /**
     * Rebuilds a past assistant turn's tool trace field by field from the untrusted body. Shape
     * validation only: unknown tool names are dropped later by CatalogAgentService, which owns the
     * registry. The caps bound scanned entries, accepted entries and payload size per call.
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
     * Flat object of scalars, dropping nested/null/over-long values: MAX_REPLAY_CALLS bounds how many
     * calls are replayed, not how big each is, and arguments are re-serialised into the model context
     * on every later turn.
     */
    private static JsonNode sanitizeAgentArgs(Map<?, ?> raw) {
        ObjectNode clean = AGENT_MAPPER.createObjectNode();
        int scanned = 0;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            // Both bounds are needed: a non-scalar value is accepted by no branch below, so without
            // the scan bound a map of nested junk is walked whole.
            if (clean.size() >= AGENT_MAX_TRACE_ARG_KEYS || ++scanned > AGENT_MAX_TRACE_SCAN) break;
            // The key is bounded too: it is re-serialised into the model context like the value.
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
