package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta la cantidad de unidades de un producto (packs/combos) a partir de
 * su nombre — ver design {@code pack-pricing-detection}.
 *
 * <p>Extraído verbatim (literal cut-paste, sin reescritura de lógica) de
 * {@code NormalizerService.detectarCantidadUnidades} y sus patrones
 * asociados (Work Unit 4 de la modularización SOLID del aggregator). Esta
 * detección es LIVE y está monitoreada por drift de distribución ML — ver
 * {@code CLAUDE.md} → "Pack/combo pricing detection — drift de distribución
 * ML". Lee {@code TORSO_KEYWORDS_FLAT}/{@code PIERNAS_KEYWORDS_FLAT} desde
 * {@link GarmentTaxonomy} (ADR-1, single source of truth compartida con
 * {@code CategoryClassifier}) y usa {@link NonTextileGuard} para el early-exit
 * "claramente no textil".</p>
 */
@Component
public class PackQuantityDetector {

    /** Tope sano: por encima de esto, el número probablemente es un modelo/SKU, no una cantidad. */
    private static final int MAX_CANTIDAD_UNIDADES = 12;

    /**
     * Marcador explícito de pack/combo/set/kit, opcionalmente seguido de "de"
     * y luego "x" o directamente el número: "pack x3", "combo x2", "pack de 3",
     * "set x2", "kit x4".
     */
    private static final Pattern PACK_KEYWORD_COUNT = Pattern.compile(
        "\\b(?:pack|combo|set|kit)\\s*(?:de\\s*)?x?\\s*(\\d{1,2})\\b");

    /**
     * Keyword pack/combo/set/kit con la prenda en el medio y el "xN" más
     * adelante: "Pack Remeras x3", "Combo Buzo Canguro x2". El hueco entre
     * keyword y "xN" se limita a 20 caracteres para que un "x2" perdido en
     * otra parte de un título largo (otro producto, otro talle) no se
     * acople falsamente con un "pack"/"combo" lejano y no relacionado.
     */
    private static final Pattern KEYWORD_NEAR_X_COUNT = Pattern.compile(
        "\\b(?:pack|combo|set|kit)\\b.{0,20}?\\bx\\s*(\\d{1,2})\\b");

    /** "N piezas/prendas/unidades": "set 2 piezas", "3 prendas", "2 unidades". */
    private static final Pattern N_PIEZAS = Pattern.compile(
        "\\b(\\d{1,2})\\s*(?:piezas|prendas|unidades)\\b");

    /**
     * Mapa singular→plural de raíces de prenda, DERIVADO de las keywords
     * canónicas ya usadas en {@code matchesTorsoBlock}/{@code matchesPiernasBlock}
     * (primer término "limpio" de cada KW_* del bloque torso/piernas). Se
     * mantiene como mapa explícito (no como lista paralela libre) para evitar
     * el riesgo de drift documentado en el design: agregar una prenda nueva al
     * clasificador NO actualiza automáticamente esta detección de cantidad,
     * pero al menos las raíces ya existentes están centralizadas en un solo lugar.
     */
    private static final Map<String, String> GARMENT_PLURAL_ROOTS = new LinkedHashMap<>();
    /** Patrones de {@link #GARMENT_PLURAL_ROOTS} precompilados una sola vez al cargar la clase. */
    private static final List<Pattern> GARMENT_PLURAL_PATTERNS = new ArrayList<>();
    static {
        GARMENT_PLURAL_ROOTS.put("remera", "remeras");
        GARMENT_PLURAL_ROOTS.put("buzo", "buzos");
        GARMENT_PLURAL_ROOTS.put("musculosa", "musculosas");
        GARMENT_PLURAL_ROOTS.put("camisa", "camisas");
        GARMENT_PLURAL_ROOTS.put("campera", "camperas");
        GARMENT_PLURAL_ROOTS.put("chomba", "chombas");
        GARMENT_PLURAL_ROOTS.put("calza", "calzas");
        GARMENT_PLURAL_ROOTS.put("jean", "jeans");
        GARMENT_PLURAL_ROOTS.put("pantalon", "pantalones");
        GARMENT_PLURAL_ROOTS.put("short", "shorts");
        GARMENT_PLURAL_ROOTS.put("bermuda", "bermudas");
        GARMENT_PLURAL_ROOTS.put("media", "medias");
        GARMENT_PLURAL_ROOTS.put("calzoncillo", "calzoncillos");
        GARMENT_PLURAL_ROOTS.put("boxer", "boxers");
        for (String plural : GARMENT_PLURAL_ROOTS.values()) {
            GARMENT_PLURAL_PATTERNS.add(Pattern.compile(
                "\\b(\\d{1,2})\\s+" + Pattern.quote(plural) + "\\b"));
        }
    }

    /**
     * Keywords de torso/piernas en una sola lista cada uno. Usados SOLO por la
     * detección de cantidad — la clasificación de categoría "Conjunto" sigue
     * usando el check booleano laxo a propósito; ahí un falso positivo es
     * cosmético (categoría mal etiquetada), pero en cantidad un falso positivo
     * corrompe el precio unitario, así que acá exigimos además un conector
     * explícito (ver {@link #COMBO_CONNECTOR}).
     *
     * <p>{@code TORSO_KEYWORDS_FLAT}/{@code PIERNAS_KEYWORDS_FLAT} viven en
     * {@link GarmentTaxonomy}, compartidas con {@code CategoryClassifier} —
     * single source of truth (ADR-1).</p>
     */
    private static final Pattern COMBO_CONNECTOR = Pattern.compile("\\+|/|\\by\\b|\\be\\b");

    /** Ventana máxima entre el final de una prenda y el inicio de la otra para considerar el conector relacionado. */
    private static final int MAX_COMBO_CONNECTOR_GAP = 30;

    /** Posición [inicio, fin) de la primera (más temprana) keyword que matchea, o null si ninguna matchea. */
    private int[] firstMatchSpan(String t, String[] keywords) {
        int bestIdx = -1, bestLen = 0;
        for (String kw : keywords) {
            int idx = t.indexOf(kw);
            if (idx >= 0 && (bestIdx == -1 || idx < bestIdx)) {
                bestIdx = idx;
                bestLen = kw.length();
            }
        }
        return bestIdx == -1 ? null : new int[] { bestIdx, bestIdx + bestLen };
    }

    /**
     * Combo de prendas distintas (torso+piernas) con conector explícito entre
     * ambas → 2 fijo. Requiere que el texto ENTRE el final de una prenda y el
     * inicio de la otra contenga un conector (ver {@link #COMBO_CONNECTOR}) y
     * que esa distancia no supere {@link #MAX_COMBO_CONNECTOR_GAP} caracteres
     * — sin esto, "Buzo Canguro Jogger Hombre" (un solo buzo cuyo corte se
     * describe como "jogger") se detectaba falsamente como pack de 2.
     */
    private boolean matchesTorsoPiernasComboConConector(String t) {
        int[] torso = firstMatchSpan(t, GarmentTaxonomy.TORSO_KEYWORDS_FLAT);
        int[] piernas = firstMatchSpan(t, GarmentTaxonomy.PIERNAS_KEYWORDS_FLAT);
        if (torso == null || piernas == null) return false;

        int gapStart = Math.min(torso[1], piernas[1]);
        int gapEnd = Math.max(torso[0], piernas[0]);
        if (gapEnd <= gapStart || gapEnd - gapStart > MAX_COMBO_CONNECTOR_GAP) return false;

        return COMBO_CONNECTOR.matcher(t.substring(gapStart, gapEnd)).find();
    }

    /**
     * Detecta la cantidad de unidades de un producto (packs/combos) a partir
     * de su nombre. Orden de prioridad: (1) keyword pack/combo/set/kit + número
     * explícito, (2) "N + prenda en plural" adyacente, (3) "N piezas/prendas/
     * unidades", (4) combo de prendas distintas (torso+piernas) con conector
     * explícito → 2 fijo.
     * Ante cualquier ambigüedad (SKU, rango de talle, conteo de colores,
     * cantidad fuera del tope sano) devuelve 1 (conservador).
     */
    public int detectar(String texto, String categoriaResuelta) {
        if (texto == null || texto.isBlank()) return 1;
        if (NonTextileGuard.esClaramenteNoTextil(texto)) return 1;

        String t = " " + AccentStripper.strip(texto.toLowerCase()) + " ";

        // Guards negativos primero: rangos de talle y conteo de colores nunca
        // deben colarse como cantidad, sin importar qué patrón los matchee.
        if (esRangoDeTalle(t) || esConteoDeColor(t)) return 1;

        Integer porKeyword = extraerCantidad(PACK_KEYWORD_COUNT, t);
        if (porKeyword != null) return cap(porKeyword);

        Integer porX = extraerCantidad(KEYWORD_NEAR_X_COUNT, t);
        if (porX != null) return cap(porX);

        Integer porPrendaPlural = detectarPrendaPluralAdyacente(t);
        if (porPrendaPlural != null) return cap(porPrendaPlural);

        Integer porPiezas = extraerCantidad(N_PIEZAS, t);
        if (porPiezas != null) return cap(porPiezas);

        if (matchesTorsoPiernasComboConConector(t)) return 2;

        return 1;
    }

    /** Devuelve la cantidad si el regex matchea, o null. No aplica el cap todavía. */
    private Integer extraerCantidad(Pattern pattern, String t) {
        Matcher m = pattern.matcher(t);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Busca un entero pequeño inmediatamente adyacente (antes) a una de las
     * raíces de prenda pluralizadas conocidas. Adyacencia estricta: el número
     * y la prenda deben estar separados solo por un espacio, evitando que un
     * número de modelo/talle alejado del sustantivo se cuele.
     */
    private Integer detectarPrendaPluralAdyacente(String t) {
        for (Pattern adyacente : GARMENT_PLURAL_PATTERNS) {
            Integer cantidad = extraerCantidad(adyacente, t);
            if (cantidad != null) return cantidad;
        }
        return null;
    }

    /** "talle 38 a 42", "talles 2 al 3", "talle 38-40": números de rango de talle, no cantidad. */
    private static final Pattern RANGO_TALLE = Pattern.compile(
        "\\btalle[s]?\\s+\\d{1,3}\\s*(?:-|a|al)\\s*\\d{1,3}\\b");

    private boolean esRangoDeTalle(String t) {
        return RANGO_TALLE.matcher(t).find();
    }

    /** "3 colores", "disponible en 2 colores": conteo de variantes de color, no cantidad de prendas. */
    private static final Pattern CONTEO_COLOR = Pattern.compile(
        "\\b\\d{1,2}\\s*colores\\b");

    private boolean esConteoDeColor(String t) {
        return CONTEO_COLOR.matcher(t).find();
    }

    private int cap(int cantidad) {
        return (cantidad >= 2 && cantidad <= MAX_CANTIDAD_UNIDADES) ? cantidad : 1;
    }
}
