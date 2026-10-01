package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

@Component
public class PackQuantityDetector {

    private static final int MAX_CANTIDAD_UNIDADES = 12;

    private static final Pattern PACK_KEYWORD_COUNT = Pattern.compile(
        "\\b(?:pack|combo|set|kit)\\s*(?:de\\s*)?x?\\s*(\\d{1,2})\\b");

    /**
     * El hueco entre keyword y "xN" se limita a 20 caracteres para que un "x2" perdido en otra
     * parte de un título largo (otro producto, otro talle) no se acople falsamente con un
     * "pack"/"combo" lejano y no relacionado.
     */
    private static final Pattern KEYWORD_NEAR_X_COUNT = Pattern.compile(
        "\\b(?:pack|combo|set|kit)\\b.{0,20}?\\bx\\s*(\\d{1,2})\\b");

    private static final Pattern N_PIEZAS = Pattern.compile(
        "\\b(\\d{1,2})\\s*(?:piezas|prendas|unidades)\\b");

    private static final Map<String, String> GARMENT_PLURAL_ROOTS = new LinkedHashMap<>();
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
     * Usados SOLO por la detección de cantidad — la clasificación de categoría "Conjunto" sigue
     * usando el check booleano laxo a propósito; ahí un falso positivo es cosmético (categoría mal
     * etiquetada), pero en cantidad un falso positivo corrompe el precio unitario, así que acá
     * exigimos además un conector explícito (ver {@link #COMBO_CONNECTOR}).
     */
    private static final Pattern COMBO_CONNECTOR = Pattern.compile("\\+|/|\\by\\b|\\be\\b");

    private static final int MAX_COMBO_CONNECTOR_GAP = 30;

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
     * Requiere que el texto ENTRE el final de una prenda y el inicio de la otra contenga un
     * conector (ver {@link #COMBO_CONNECTOR}) y que esa distancia no supere
     * {@link #MAX_COMBO_CONNECTOR_GAP} caracteres — sin esto, "Buzo Canguro Jogger Hombre" (un solo
     * buzo cuyo corte se describe como "jogger") se detectaba falsamente como pack de 2.
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

    /** Orden de prioridad: */
    public int detectar(String texto, String categoriaResuelta) {
        if (StringUtils.isBlank(texto)) return 1;
        if (NonTextileGuard.esClaramenteNoTextil(texto)) return 1;

        String t = " " + AccentStripper.strip(texto.toLowerCase()) + " ";

        // Guards negativos primero: rangos de talle y conteo de colores nunca deben colarse como
        // cantidad, sin importar qué patrón los matchee.
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
     * Adyacencia estricta: el número y la prenda deben estar separados solo por un espacio,
     * evitando que un número de modelo/talle alejado del sustantivo se cuele.
     */
    private Integer detectarPrendaPluralAdyacente(String t) {
        for (Pattern adyacente : GARMENT_PLURAL_PATTERNS) {
            Integer cantidad = extraerCantidad(adyacente, t);
            if (cantidad != null) return cantidad;
        }
        return null;
    }

    private static final Pattern RANGO_TALLE = Pattern.compile(
        "\\btalle[s]?\\s+\\d{1,3}\\s*(?:-|a|al)\\s*\\d{1,3}\\b");

    private boolean esRangoDeTalle(String t) {
        return RANGO_TALLE.matcher(t).find();
    }

    private static final Pattern CONTEO_COLOR = Pattern.compile(
        "\\b\\d{1,2}\\s*colores\\b");

    private boolean esConteoDeColor(String t) {
        return CONTEO_COLOR.matcher(t).find();
    }

    private int cap(int cantidad) {
        return (cantidad >= 2 && cantidad <= MAX_CANTIDAD_UNIDADES) ? cantidad : 1;
    }
}
