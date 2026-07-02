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

    private static final List<String> MARCAS = List.of(
        "Nike","Adidas","Puma","Reebok","New Balance","Asics","Saucony","Brooks",
        "Hoka","On Running","Salomon","Mizuno","Under Armour","Fila","Umbro",
        "Vans","Converse","DC","Etnies","Volcom","Quiksilver","Billabong",
        "The North Face","Columbia","Patagonia","Timberland","Merrell",
        "Topper","Flecha","Jaguar","Gola","Penalty","Olympikus",
        "Lacoste","Tommy","Calvin Klein","Levi's","Levis","Wrangler",
        "Champion","Kappa","Ellesse","Le Coq Sportif","Fred Perry",
        "Caterpillar","Keen","Palladium","Crocs","Birkenstock",
        "Bulks","Fuark","Harvey Willys","Harvey"
    );

    // Word-boundary patterns (no substring matches) — evita falsos positivos
    // como "DC" matcheando dentro de "Hardcore" o "HDCP" (ver bug category-brand-quality-fixes).
    private static final List<Pattern> MARCA_PATTERNS = MARCAS.stream()
            .map(m -> Pattern.compile("\\b" + Pattern.quote(m.toLowerCase()) + "\\b"))
            .collect(Collectors.toList());

    public String extraer(String nombre, String sitio) {
        if (nombre == null || nombre.isBlank()) return "";
        String lower = nombre.toLowerCase();

        for (int i = 0; i < MARCAS.size(); i++) {
            if (MARCA_PATTERNS.get(i).matcher(lower).find()) return MARCAS.get(i);
        }

        return sitio != null ? sitio : "";
    }
}
