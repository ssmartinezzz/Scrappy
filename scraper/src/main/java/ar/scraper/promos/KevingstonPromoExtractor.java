package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payload: a listing row. {@code cuotas} reads "6 cuotas de $32.666,67*" and never says "sin interés",
 * so it counts only when quantity x amount equals {@code precio} within one peso. {@code porcentaje}
 * is the discount already inside {@code precio}, not a payment promo. Medio: the generic card.
 */
public final class KevingstonPromoExtractor implements ProductPromoExtractor {

    private static final Pattern CUOTAS = Pattern.compile("(\\d+)\\s+cuotas\\s+de\\s+\\$\\s*([\\d.]+(?:,\\d+)?)");

    @Override
    public List<PromoFinanciacion> extraer(JsonNode fila, LocalDate hoy) {
        Matcher m = CUOTAS.matcher(fila.path("cuotas").asText(""));
        if (!m.find()) return List.of();
        int n = Integer.parseInt(m.group(1));
        double monto = Double.parseDouble(m.group(2).replace(".", "").replace(',', '.'));
        double precio = fila.path("precio").asDouble(0);
        if (n < 2 || precio <= 0 || Math.abs(n * monto - precio) > 1) return List.of();
        return List.of(PromosDeProducto.cuotasSinInteresGenericas(n));
    }
}
