package ar.scraper.ml;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

@Component
public class MlEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(MlEnricher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<Product> enriquecer(List<Product> productos, JsonNode mlOutput) {
        if (mlOutput == null || mlOutput.isNull() || mlOutput.isEmpty()) return productos;
        JsonNode scores = mlOutput.path("scores");
        if (scores.isMissingNode()) return productos;

        int enriquecidos = 0;
        int catRefinadas  = 0;
        int generosRellenados = 0;
        List<Product> result = new ArrayList<>();

        for (Product p : productos) {
            String key = StringUtils.isNotBlank(p.url()) ? p.url() : p.nombre();
            JsonNode s  = scores.path(key);
            if (s.isMissingNode()) { result.add(p); continue; }

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
                    s.path("ofertaReal").asBoolean(
                        s.path("descuentoSig").asBoolean(false)
                        && s.path("ratio").asDouble(1.0) >= 1.15),
                    s.path("tendenciaPrecio").asText("estable"),
                    s.path("pctil").asInt(50),
                    s.path("mzScore").asDouble(0.0),
                    s.path("segment").asText("standard")
            );

            String catFinal = p.categoria();
            String catML    = s.path("categoriaML").asText("");
            double catConf  = s.path("catMLConf").asDouble(0.0);
            if (!catML.isBlank() && catConf >= 0.80) {
                catFinal = catML;
                catRefinadas++;
            }

            String generoFinal = p.genero();
            if (StringUtils.isBlank(generoFinal)) {
                String gML   = s.path("generoML").asText("");
                double gConf = s.path("genImgConf").asDouble(0.0);
                if (("hombre".equals(gML) || "mujer".equals(gML)) && gConf >= 0.80) {
                    generoFinal = gML;
                    generosRellenados++;
                }
                // "unisex" de imagen (sentinel bajo-umbral) deja el género en blanco
            }

            // ── Atributos visuales derivados de imagen (fit/estampado/escote/color) ── RELY-001
            // fix: aditivo por campo, no un reemplazo incondicional. ml_pipeline.py solo puebla
            // estas 4 keys para el subconjunto gateado por needs_image_fallback (capado a 400 por
            // run) — el resto del score trae blank/missing en estos campos aunque el producto SÍ
            // tenga visual persistido de un run anterior o del backfill CLI.
            Product.VisualAttrs visualPrevio = p.visual() != null ? p.visual() : Product.VisualAttrs.EMPTY;
            Product.VisualAttrs visual = new Product.VisualAttrs(
                    valorScoreOPrevio(s.path("fit").asText(""), visualPrevio.fit()),
                    valorScoreOPrevio(s.path("print").asText(""), visualPrevio.estampado()),
                    valorScoreOPrevio(s.path("neckline").asText(""), visualPrevio.escote()),
                    valorScoreOPrevio(s.path("color").asText(""), visualPrevio.colorDominante())
            );

            Product enriched = Product.builder()
                    .sitio(p.sitio())
                    .nombre(p.nombre())
                    .precio(p.precio())
                    .precioOriginal(p.precioOriginal())
                    .url(p.url())
                    .imagenUrl(p.imagenUrl())
                    .categoria(catFinal)
                    .genero(generoFinal)
                    .talles(p.talles())
                    .ml(ml)
                    .marca(p.marca())
                    .rubro(p.rubro() != null ? p.rubro() : "indumentaria")
                    .gymrat(p.gymrat())
                    .marcaPremium(p.marcaPremium())
                    .senal(p.senal())
                    .finan(p.finan())
                    .cantidadUnidades(p.cantidadUnidades())
                    .subCategoria(p.subCategoria() != null ? p.subCategoria() : "")
                    .visual(visual)
                    .build();
            result.add(enriched);
            enriquecidos++;
        }

        LOG.info("[ML] {} productos enriquecidos | {} categorías refinadas por modelo | {} géneros rellenados por imagen",
                 enriquecidos, catRefinadas, generosRellenados);
        return result;
    }

    private static String valorScoreOPrevio(String valorScore, String valorPrevio) {
        if (StringUtils.isNotBlank(valorScore)) return valorScore;
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
                n.put("precioOriginal", p.precioOriginal());
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
