package ar.scraper.promos;

import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * Payload: a Tiendanube product. The merchant writes the promo into the SEO description
 * ("... 20% de descuento con transferencia."); the text parser owns reading it.
 */
public final class InproPromoExtractor implements ProductPromoExtractor {

    @Override
    public List<PromoFinanciacion> extraer(JsonNode producto, LocalDate hoy) {
        JsonNode d = producto.path("seo_description");
        String texto = StringUtils.defaultIfBlank(d.path("es").asText(""), d.path("value").asText(""));
        return PromoTextParser.parsear(texto, Alcance.PRODUCTO, hoy);
    }
}
