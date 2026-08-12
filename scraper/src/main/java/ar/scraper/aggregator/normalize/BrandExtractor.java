package ar.scraper.aggregator.normalize;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Curated-brand extraction with site fallback.
 *
 * <p>Extraído verbatim de {@code NormalizerService.extraerMarca} + {@code MARCAS}
 * + {@code MARCA_PATTERNS} (Work Unit 6 de la modularización SOLID del
 * aggregator) — pure relocation, no behavior change. The original method
 * never called {@code normalizarAcentos} (brand matching operates on
 * lower-cased text only, via word-boundary regex), so there is no accent
 * chain to delegate to {@link ar.scraper.aggregator.text.AccentStripper}
 * here — see migration tracker for this design-vs-actual-code note.</p>
 */
@Component
public class BrandExtractor {

    // ══════════════════════════════════════════════════════════════════
    // MARCAS conocidas en Argentina
    // ══════════════════════════════════════════════════════════════════

    // Público (close-1nf-and-3nf-foundation, design DD8): MarcasSiteIntersectionTest
    // necesita esta lista para probar, sin DB, que la excepción de V19
    // (Bulks/Fuark/Harvey) es EXACTAMENTE la intersección real contra
    // sitio.nombre — pura ampliación de visibilidad, sin cambio de comportamiento.
    public static final List<String> MARCAS = List.of(
        "Nike","Adidas","Puma","Reebok","New Balance","Asics","Saucony","Brooks",
        "Hoka","On Running","Salomon","Mizuno","Under Armour","Fila","Umbro",
        "Vans","Converse","DC","Etnies","Volcom","Quiksilver","Billabong",
        "The North Face","Columbia","Patagonia","Timberland","Merrell",
        "Topper","Flecha","Jaguar","Gola","Penalty","Olympikus",
        "Lacoste","Tommy","Calvin Klein","Levi's","Levis","Wrangler",
        "Champion","Kappa","Ellesse","Le Coq Sportif","Fred Perry",
        "Caterpillar","Keen","Palladium","Crocs","Birkenstock",
        "Bulks","Fuark","Harvey Willys","Harvey",
        // Suplementos. Sin estas entradas la lista era 100% indumentaria y calzado,
        // así que TODO suplemento caía al fallback por sitio: una whey de ENA vendida
        // por Entreno quedaba con marca "Entreno". Eso dejaba muerta la preferencia de
        // marca de SupplementCombo, que compara contra el nombre de la marca real.
        // Sólo formas que se sostienen solas: "Star"/"Gold" pelados matchearían
        // "All Star" y "Gold Standard", el mismo falso positivo que el de DC.
        "Gold Nutrition","Star Nutrition","Xtrenght","ENA","BSA"
    );

    // Word-boundary patterns (no substring matches) — evita falsos positivos
    // como "DC" matcheando dentro de "Hardcore" o "HDCP" (ver bug category-brand-quality-fixes).
    private static final List<Pattern> MARCA_PATTERNS = MARCAS.stream()
            .map(m -> Pattern.compile("\\b" + Pattern.quote(m.toLowerCase()) + "\\b"))
            .collect(Collectors.toList());

    /**
     * @param sitio no longer used to backfill an unmatched brand (V19, design
     *              DD8) — kept in the signature because it is genuinely part
     *              of the extraction context callers already have on hand,
     *              not because this method still reads it for its own logic.
     */
    public String extraer(String nombre, String sitio) {
        if (nombre == null || nombre.isBlank()) return "";
        String lower = nombre.toLowerCase();

        for (int i = 0; i < MARCAS.size(); i++) {
            if (MARCA_PATTERNS.get(i).matcher(lower).find()) return MARCAS.get(i);
        }

        // Abstención (CODE-5): un sitio NO es una marca. Devolver el nombre
        // del sitio acá era la mentira que V19 existe para dejar de contar.
        return "";
    }
}
