package ar.scraper.aggregator.normalize;

import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * Medido sobre las 6540 filas activas: 12 valores fuera del canon, 478 productos, de los cuales 363
 * eran el fallback genérico.
 */
public final class CategoryAliases {

    private CategoryAliases() {
    }

    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("abrigos", "Campera"),
            Map.entry("coat", "Campera"),
            Map.entry("bufandon", "Bufanda"),
            Map.entry("remeron", "Remera"),
            Map.entry("mini", "Bolso"),
            Map.entry("neceser", "Bolso"),
            Map.entry("pc", "PC"),
            // Basura de breadcrumb: el producto no es de esa categoría
            Map.entry("porta", "Otros"),
            Map.entry("cooling", "Otros"),
            Map.entry("tarjetas", "Otros"),
            Map.entry("indumentaria", "Otros")
    );

    /**
     * La categoría canónica para {@code valor}, o {@code null} si no hay alias. Case-insensitive:
     */
    public static String canonical(String valor) {
        if (StringUtils.isBlank(valor)) return null;
        return ALIAS.get(valor.trim().toLowerCase(Locale.ROOT));
    }

    public static Map<String, String> todos() {
        return ALIAS;
    }
}
