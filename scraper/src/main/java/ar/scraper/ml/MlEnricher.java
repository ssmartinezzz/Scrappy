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
        List<Product> result = new ArrayList<>();

        for (Product p : productos) {
            String key = (p.url() != null && !p.url().isBlank()) ? p.url() : p.nombre();
            JsonNode s  = scores.path(key);
            if (s.isMissingNode()) { result.add(p); continue; }

            Product.MlScore ml = new Product.MlScore(
                    s.path("composite").asInt(s.path("pctil").asInt(50)),
                    s.path("badge").asText(""),
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

            Product enriched = new Product(
                p.sitio(), p.nombre(), p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), catFinal, p.genero(), p.talles(),
                ml, p.marca(), p.rubro() != null ? p.rubro() : "indumentaria",
                p.gymrat(), p.marcaPremium(), p.senal(), p.finan(), p.cantidadUnidades(),
                p.subCategoria() != null ? p.subCategoria() : "", p.visual()
            );
            result.add(enriched);
            enriquecidos++;
        }

        LOG.info("[ML] {} productos enriquecidos | {} categorías refinadas por modelo",
                 enriquecidos, catRefinadas);
        return result;
    }

    /** Backwards-compatible overload sin DB */
    public List<Product> enriquecer(List<Product> productos, JsonNode mlOutput) {
        return enriquecer(productos, mlOutput, null);
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
