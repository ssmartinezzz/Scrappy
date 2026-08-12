package ar.scraper.aggregator.normalize;

import java.util.Locale;
import java.util.Map;

/**
 * Los valores de `categoria` que la base tiene HOY y que no pertenecen al
 * vocabulario canónico, con su destino.
 *
 * <p>Todos salieron de la misma rama de {@link CategoryClassifier}: cuando el
 * breadcrumb de la tienda no matcheaba ningún keyword, se devolvía su PRIMERA
 * PALABRA capitalizada. Eso no produjo categorías raras pero válidas — produjo
 * categorías <b>equivocadas</b> ({@code Mini} para "Mini Morral", {@code Pc}
 * para una microSD, {@code Cooling} para un adaptador) y duplicados de
 * categorías que ya existían ({@code Coat}/{@code Abrigos} contra
 * {@code Campera}).</p>
 *
 * <p>Medido sobre las 6540 filas activas: 12 valores fuera del canon, 478
 * productos, de los cuales 363 eran el fallback genérico. Cerrar el
 * vocabulario no pierde información — corrige productos mal archivados que
 * ningún filtro razonable encontraba.</p>
 *
 * <p><b>Esta tabla es de datos, no de reglas.</b> No crece cuando aparece una
 * tienda nueva: lo que no matchea cae en {@code Otros}, a la vista. Si una
 * categoría aparece seguido en {@code Otros}, se la agrega al canon a
 * propósito, que es una decisión de producto y no un accidente de parsing.</p>
 */
public final class CategoryAliases {

    private CategoryAliases() {
    }

    /** Clave en minúscula → categoría canónica. */
    private static final Map<String, String> ALIAS = Map.ofEntries(
            // Duplicados de una categoría que ya existe
            Map.entry("abrigos", "Campera"),      // "Trench - Camel"
            Map.entry("coat", "Campera"),         // "Coat Duffel Negro"
            Map.entry("bufandon", "Bufanda"),
            Map.entry("remeron", "Remera"),       // el largo/oversize lo lleva sub_categoria
            Map.entry("mini", "Bolso"),           // "Mini Morral Lindor"
            Map.entry("neceser", "Bolso"),
            // Diferencia de capitalización contra el canon
            Map.entry("pc", "PC"),
            // Basura de breadcrumb: el producto no es de esa categoría
            Map.entry("porta", "Otros"),          // "Porta Celular"
            Map.entry("cooling", "Otros"),        // "ADAPTADOR COOLERMASTER..."
            Map.entry("tarjetas", "Otros"),       // "Gift Cards VCP"
            // El viejo fallback genérico: era un RUBRO usado como categoría,
            // o sea "no sé" disfrazado de dato.
            Map.entry("indumentaria", "Otros")
    );

    /**
     * La categoría canónica para {@code valor}, o {@code null} si no hay alias.
     * Case-insensitive: {@code Pc}, {@code PC} y {@code pc} son lo mismo.
     */
    public static String canonical(String valor) {
        if (valor == null || valor.isBlank()) return null;
        return ALIAS.get(valor.trim().toLowerCase(Locale.ROOT));
    }

    /** La tabla completa, para la migración de datos y sus tests. */
    public static Map<String, String> todos() {
        return ALIAS;
    }
}
