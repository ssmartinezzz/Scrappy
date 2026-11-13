package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads the product-scoped payment promos out of the payload a page class already holds, one
 * implementation per platform. Never issues a request. Abstains (empty list) when the payload does
 * not prove a promo; medio names always go through {@link MediosDePago}.
 */
public interface ProductPromoExtractor {

    /** {@code hoy} anchors the year of dates read from free text. */
    List<PromoFinanciacion> extraer(JsonNode payload, LocalDate hoy);
}
