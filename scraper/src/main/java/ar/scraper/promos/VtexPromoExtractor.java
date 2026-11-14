package ar.scraper.promos;

import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Payload: the {@code commertialOffer} the price came from. Per resolvable payment system, the most
 * interest-free installments (2 or more); a system the vocabulary does not know is skipped. The
 * offer's teaser and highlight names go through the text parser, which abstains on the internal
 * campaign flags stores put there.
 */
public final class VtexPromoExtractor implements ProductPromoExtractor {

    // The .NET serialization behind some stores leaks the backing-field name instead of "Name".
    private static final List<String> CAMPOS_NOMBRE = List.of("Name", "<Name>k__BackingField");

    @Override
    public List<PromoFinanciacion> extraer(JsonNode oferta, LocalDate hoy) {
        Set<PromoFinanciacion> out = new LinkedHashSet<>(cuotas(oferta.path("Installments")));
        for (String campo : List.of("PromotionTeasers", "DiscountHighLight")) {
            for (JsonNode n : oferta.path(campo)) {
                for (String nombre : CAMPOS_NOMBRE) {
                    out.addAll(PromoTextParser.parsear(n.path(nombre).asText(""), Alcance.PRODUCTO, hoy));
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static List<PromoFinanciacion> cuotas(JsonNode installments) {
        Map<String, Integer> maxPorSistema = new LinkedHashMap<>();
        for (JsonNode i : installments) {
            int n = i.path("NumberOfInstallments").asInt(0);
            if (n < 2 || i.path("InterestRate").asDouble(-1) != 0) continue;
            maxPorSistema.merge(i.path("PaymentSystemName").asText(""), n, Math::max);
        }
        List<PromoFinanciacion> out = new ArrayList<>();
        maxPorSistema.forEach((sistema, n) -> PromosDeProducto.cuotasSinInteres(sistema, n).ifPresent(out::add));
        return out;
    }
}
