package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Payload: an API product. The store's own text says {@code price.special} is only for payment by
 * bank transfer or deposit and {@code price.list} is what every other medium pays, so special below
 * list is a transfer discount of that percentage of list.
 */
public final class GamerFactoryPromoExtractor implements ProductPromoExtractor {

    @Override
    public List<PromoFinanciacion> extraer(JsonNode producto, LocalDate hoy) {
        double especial = producto.path("price").path("special").path("ARS").asDouble(0);
        double lista = producto.path("price").path("list").path("ARS").asDouble(0);
        if (especial <= 0 || especial >= lista) return List.of();
        BigDecimal pct = BigDecimal.valueOf((lista - especial) / lista * 100).setScale(2, RoundingMode.HALF_UP);
        return List.of(PromosDeProducto.descuento("Transferencia", pct));
    }
}
