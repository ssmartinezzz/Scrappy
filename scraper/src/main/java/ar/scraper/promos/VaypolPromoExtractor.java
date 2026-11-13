package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

/**
 * Payload: a listing item. {@code all_prices.installments} is {quantity, value} with no interest
 * flag and no medio, so it counts as interest-free only when quantity x value reproduces the price
 * the page uses, within one peso per installment (the store floors the value). Medio: the generic card.
 */
public final class VaypolPromoExtractor implements ProductPromoExtractor {

    @Override
    public List<PromoFinanciacion> extraer(JsonNode item, LocalDate hoy) {
        JsonNode precios = item.path("all_prices");
        double oferta = precios.path("sale_price").asDouble(0);
        double precio = oferta > 0 ? oferta : precios.path("original").asDouble(0);
        JsonNode cuotas = precios.path("installments");
        int n = cuotas.path("quantity").asInt(0);
        double valor = cuotas.path("value").asDouble(0);
        if (precio <= 0 || n < 2 || valor <= 0 || Math.abs(n * valor - precio) >= n) return List.of();
        return List.of(PromosDeProducto.cuotasSinInteresGenericas(n));
    }
}
