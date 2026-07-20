package ar.scraper.ml;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MlEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(MlEnricher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<Product> enriquecer(List<Product> productos, JsonNode mlOutput,
                                    ar.scraper.db.DatabaseService db) {
        if (mlOutput == null || mlOutput.isNull() || mlOutput.isEmpty()) return productos;
        JsonNode scores = mlOutput.path("scores");
        if (scores.isMissingNode()) return productos;

        int enriquecidos = 0;
        int catRefinadas  = 0;
        int generosRellenados = 0;
        List<Product> result = new ArrayList<>();

        for (Product p : productos) {
            String key = (p.url() != null && !p.url().isBlank()) ? p.url() : p.nombre();
            JsonNode s  = scores.path(key);
            if (s.isMissingNode()) { result.add(p); continue; }

            // ── Badge set (badges-oportunidades-revamp D3) ──────────────────
            // ml_pipeline.py emits BOTH 'badge' (principal, back-compat string)
            // and 'badges' (ordered, principal-first list). A cached/older
            // ml_output that still lacks 'badges' (pre-multi-badge) falls back
            // to a one-element list derived from 'badge'.
            List<String> badges = new ArrayList<>();
            JsonNode badgesNode = s.path("badges");
            if (badgesNode.isArray()) {
                for (JsonNode bn : badgesNode) {
                    String b = bn.asText("");
                    if (!b.isBlank()) badges.add(b);
                }
            } else {
                String badgeFallback = s.path("badge").asText("");
                if (!badgeFallback.isBlank()) badges.add(badgeFallback);
            }

            Product.MlScore ml = new Product.MlScore(
                    s.path("composite").asInt(s.path("pctil").asInt(50)),
                    badges,
                    // Fallback de compatibilidad: pipelines previos a tendencias-clusters-fix
                    // NO emitían 'ofertaReal'. Si la key falta (output viejo restaurado desde
                    // la DB), derivamos la regla localmente para no perder el badge.
                    // La fuente de verdad es ml_pipeline.py (scores[pid].ofertaReal).
                    s.path("ofertaReal").asBoolean(
                        s.path("descuentoSig").asBoolean(false)
                        && s.path("ratio").asDouble(1.0) >= 1.15),
                    s.path("tendenciaPrecio").asText("estable"),
                    s.path("pctil").asInt(50),
                    s.path("mzScore").asDouble(0.0),
                    s.path("segment").asText("standard")
            );

            // ── Aplicar categoría refinada por modelo ML ──────────────────
            String catFinal = p.categoria();
            String catML    = s.path("categoriaML").asText("");
            double catConf  = s.path("catMLConf").asDouble(0.0);
            if (!catML.isBlank() && catConf >= 0.80) {
                catFinal = catML;
                catRefinadas++;
            }

            // ── Rellenar género vía imagen SOLO cuando el texto no dice nada ──
            // Invariante text-wins (PR4 judgment-day, A-001/B-001): un género
            // ya resuelto por texto NUNCA se pisa con la señal de imagen.
            String generoFinal = p.genero();
            if (generoFinal == null || generoFinal.isBlank()) {
                String gML   = s.path("generoML").asText("");
                double gConf = s.path("genImgConf").asDouble(0.0);
                if (("hombre".equals(gML) || "mujer".equals(gML)) && gConf >= 0.80) {
                    generoFinal = gML; // señal de imagen decisiva rellena un hueco de texto
                    generosRellenados++;
                }
                // "unisex" de imagen (sentinel bajo-umbral) deja el género en blanco
            }

            // ── Atributos visuales derivados de imagen (fit/estampado/escote/color) ──
            // RELY-001 fix: aditivo por campo, no un reemplazo incondicional.
            // ml_pipeline.py solo puebla estas 4 keys para el subconjunto gateado
            // por needs_image_fallback (capado a 400 por run) — el resto del score
            // trae blank/missing en estos campos aunque el producto SÍ tenga visual
            // persistido de un run anterior o del backfill CLI. Si acá se reemplazara
            // incondicionalmente (como antes), cada scrape regular volvería a ""
            // los visual attrs de todo lo que no entró en el subconjunto de ESTE run.
            // Por campo: valor del score si no está blank, si no se preserva
            // p.visual() — mismo invariante aditivo que ml_embeddings.py
            // (ml_embeddings.py:660-676, "_persist_visual_attrs": "This CLI must
            // only ever ADD signal, never remove it"). ml_pipeline.py ya remapea
            // las claves en Python (estampado->print, escote->neckline,
            // color_dominante->color), así que acá se leen verbatim, sin re-mapeo.
            Product.VisualAttrs visualPrevio = p.visual() != null ? p.visual() : Product.VisualAttrs.EMPTY;
            Product.VisualAttrs visual = new Product.VisualAttrs(
                    valorScoreOPrevio(s.path("fit").asText(""), visualPrevio.fit()),
                    valorScoreOPrevio(s.path("print").asText(""), visualPrevio.estampado()),
                    valorScoreOPrevio(s.path("neckline").asText(""), visualPrevio.escote()),
                    valorScoreOPrevio(s.path("color").asText(""), visualPrevio.colorDominante())
            );

            Product enriched = new Product(
                p.sitio(), p.nombre(), p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), catFinal, generoFinal, p.talles(),
                ml, p.marca(), p.rubro() != null ? p.rubro() : "indumentaria",
                p.gymrat(), p.marcaPremium(), p.senal(), p.finan(), p.cantidadUnidades(),
                p.subCategoria() != null ? p.subCategoria() : "", visual
            );
            result.add(enriched);
            enriquecidos++;
        }

        LOG.info("[ML] {} productos enriquecidos | {} categorías refinadas por modelo | {} géneros rellenados por imagen",
                 enriquecidos, catRefinadas, generosRellenados);
        return result;
    }

    /** Backwards-compatible overload sin DB */
    public List<Product> enriquecer(List<Product> productos, JsonNode mlOutput) {
        return enriquecer(productos, mlOutput, null);
    }

    /**
     * Per-field additive-preserve seam (RELY-001): a blank score value means
     * "this run's ML pass produced no signal for this field" (missing key,
     * explicit {@code ""}, or the product wasn't in this run's gated
     * needs_image_fallback subset) rather than "the model confidently
     * observed nothing" — so it must never overwrite a previously persisted
     * non-blank value.
     */
    private static String valorScoreOPrevio(String valorScore, String valorPrevio) {
        if (valorScore != null && !valorScore.isBlank()) return valorScore;
        return valorPrevio != null ? valorPrevio : "";
    }

    public String serializarProductos(List<Product> productos) {
        try {
            var arr = MAPPER.createArrayNode();
            for (Product p : productos) {
                var n = MAPPER.createObjectNode();
                n.put("url",            p.url()            != null ? p.url()            : "");
                n.put("nombre",         p.nombre());
                n.put("precio",         p.precio());
                n.put("precioOriginal", p.precioOriginal() != null ? p.precioOriginal() : "");
                n.put("categoria",      p.categoria()      != null ? p.categoria()      : "");
                n.put("genero",         p.genero()         != null ? p.genero()         : "");
                n.put("sitio",          p.sitio());
                n.put("marca",          p.marca()          != null ? p.marca()          : "");
                n.put("img",            p.imagenUrl()      != null ? p.imagenUrl()      : "");
                arr.add(n);
            }
            return MAPPER.writeValueAsString(arr);
        } catch (Exception e) {
            LOG.warn("[ML] Error serializando: {}", e.getMessage());
            return "[]";
        }
    }
}
