package ar.scraper.promos;

import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.PromosDelSitio;
import ar.scraper.model.promo.TipoPromo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Store-wide promos of a Tiendanube storefront from ONE read of its home. The home carries, per product,
 * {@code data-variants}: {@code installments_data} per gateway (interest-free cuotas), the price with the
 * payment discount, and which payment methods show that discount. Anything stated there is proven from the
 * data (the discount percentage must come out of price vs. discounted price, equal across products);
 * the announcement bar and service blocks go through {@link PromoTextParser}. Structured promos come
 * first, so on a duplicate natural key the proven one wins.
 */
public final class TiendanubeSitePromoSource implements SitePromoSource {

    private static final Logger log = LoggerFactory.getLogger(TiendanubeSitePromoSource.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CUOTAS_MIN = 2;
    /** Per-product percentages may differ by cents of rounding; a wider spread means no single rule. */
    private static final BigDecimal TOLERANCIA_PCT = new BigDecimal("0.3");

    @Override
    public PromosDelSitio leer(String baseUrl, PaginaWeb web, LocalDate hoy) {
        try {
            Document home = PaginasLeidas.leer(web, baseUrl);
            Set<PromoFinanciacion> promos = new LinkedHashSet<>();
            List<JsonNode> variantes = variantes(home);
            promos.addAll(cuotasPorPasarela(variantes));
            promos.addAll(descuentoDePago(variantes));
            promos.addAll(BannerSitePromoSource.parsear(TextoVisible.lineasDePromo(home), false, hoy));
            return PromosDelSitio.leidas(new ArrayList<>(promos));
        } catch (RuntimeException e) {
            log.warn("[promos] lectura de {} fallida: {}", baseUrl, e.getMessage());
            return PromosDelSitio.NO_LEIDO;
        }
    }

    private static List<JsonNode> variantes(Document home) {
        List<JsonNode> out = new ArrayList<>();
        for (Element el : home.select("[data-variants]")) {
            try {
                JsonNode arr = JSON.readTree(el.attr("data-variants"));
                if (arr.isArray()) arr.forEach(out::add);
            } catch (Exception e) {
                log.debug("[promos] data-variants ilegible: {}", e.getMessage());
            }
        }
        return out;
    }

    /** Max interest-free cuotas (>= 2) per resolvable gateway, over every variant seen. */
    private static List<PromoFinanciacion> cuotasPorPasarela(List<JsonNode> variantes) {
        Map<String, Integer> maximo = new LinkedHashMap<>();
        for (JsonNode v : variantes) {
            JsonNode datos = parsearAnidado(v.path("installments_data"));
            for (Iterator<Map.Entry<String, JsonNode>> it = datos.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> pasarela = it.next();
                for (Iterator<Map.Entry<String, JsonNode>> c = pasarela.getValue().fields(); c.hasNext(); ) {
                    Map.Entry<String, JsonNode> cuota = c.next();
                    int n = StringUtils.isNumeric(cuota.getKey()) ? Integer.parseInt(cuota.getKey()) : 0;
                    if (n >= CUOTAS_MIN && cuota.getValue().path("without_interests").asBoolean(false)) {
                        maximo.merge(pasarela.getKey(), n, Math::max);
                    }
                }
            }
        }
        List<PromoFinanciacion> out = new ArrayList<>();
        maximo.forEach((pasarela, n) -> MediosDePago.resolver(pasarela).ifPresent(medio -> out.add(
                new PromoFinanciacion(medio.nombre(), medio.tipo(), TipoPromo.CUOTAS_SIN_INTERES, n, null,
                        Alcance.SITIO, null, null, "Tiendanube installments_data: " + pasarela))));
        return out;
    }

    private static JsonNode parsearAnidado(JsonNode nodo) {
        if (nodo.isObject()) return nodo;
        if (!nodo.isTextual() || nodo.asText().isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(nodo.asText());
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    /**
     * The payment discount is a percentage only when every plain-priced variant agrees on it, and it is
     * attributed to the methods the store's own config says show it (wire_transfer, credit_card, ...);
     * a method the vocabulary does not know (wallet, redirect) is abstention.
     */
    private static List<PromoFinanciacion> descuentoDePago(List<JsonNode> variantes) {
        List<BigDecimal> pcts = new ArrayList<>();
        Set<String> metodos = new LinkedHashSet<>();
        for (JsonNode v : variantes) {
            metodos.addAll(metodosConDescuento(v.path("popup_discount_visibility")));
            BigDecimal precio = v.path("price_number").isNumber() ? v.path("price_number").decimalValue() : null;
            BigDecimal conDescuento = dinero(v.path("price_with_payment_discount_short").asText(null));
            if (v.path("has_promotional_price").asBoolean(false) || precio == null || precio.signum() <= 0
                    || conDescuento == null || conDescuento.compareTo(precio) >= 0) continue;
            pcts.add(BigDecimal.ONE.subtract(conDescuento.divide(precio, 6, RoundingMode.HALF_UP))
                    .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
        }
        if (pcts.isEmpty()) return List.of();
        BigDecimal min = pcts.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = pcts.stream().max(BigDecimal::compareTo).orElseThrow();
        if (max.subtract(min).compareTo(TOLERANCIA_PCT) > 0) return List.of();
        BigDecimal pct = max.add(min).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);
        if (pct.subtract(min).abs().compareTo(TOLERANCIA_PCT) > 0) pct = min;
        List<PromoFinanciacion> out = new ArrayList<>();
        for (String metodo : metodos) {
            BigDecimal valor = pct;
            MediosDePago.resolver(metodo).ifPresent(medio -> out.add(
                    new PromoFinanciacion(medio.nombre(), medio.tipo(), TipoPromo.DESCUENTO, null, valor,
                            Alcance.SITIO, null, null, "Tiendanube price_with_payment_discount: " + metodo)));
        }
        return out;
    }

    private static Set<String> metodosConDescuento(JsonNode visibilidad) {
        Set<String> out = new LinkedHashSet<>();
        for (Iterator<JsonNode> pasarelas = visibilidad.elements(); pasarelas.hasNext(); ) {
            JsonNode metodos = pasarelas.next().path("methods");
            for (Iterator<Map.Entry<String, JsonNode>> it = metodos.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> m = it.next();
                if (m.getValue().path("show_discount").asBoolean(false)) out.add(m.getKey());
            }
        }
        return out;
    }

    /** "$62.991,00" / "$220.150" -> number; null when it is not an Argentine-formatted amount. */
    private static BigDecimal dinero(String texto) {
        if (StringUtils.isBlank(texto)) return null;
        String limpio = texto.replaceAll("[^0-9.,]", "").replace(".", "").replace(',', '.');
        try {
            return limpio.isEmpty() ? null : new BigDecimal(limpio);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
