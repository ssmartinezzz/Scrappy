package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Gender resolution with feminine-coded category override + infantil guard.
 *
 * <p>Extraído verbatim de {@code NormalizerService.normalizarGenero} +
 * {@code FEMININE_CODED_CATEGORIES} (Work Unit 6 de la modularización SOLID
 * del aggregator) — pure relocation, no behavior change. The local
 * {@code normalizarAcentos} helper now delegates to
 * {@link AccentStripper#strip} (ADR-4) instead of duplicating the 6-replacement
 * regex chain — same output, one fewer copy of the shared chain.</p>
 */
@Component
public class GenderResolver {

    // Categorias cuya naturaleza es predominantemente femenina en este catálogo
    // (confirmado por revisión del usuario). Cuando una de estas categorias aparece
    // SIN señal masculina explícita en el nombre del producto, el género se fuerza a
    // "mujer" antes de que el combined-check (que incluye raw VTEX) lo pise.
    // Ver outfits-v2 design — R1/T1.
    private static final Set<String> FEMININE_CODED_CATEGORIES =
            Set.of("Calza", "Pollera", "Vestido", "Enterito", "Corpino", "Malla");

    public String resolver(String raw, String nombre, String categoria) {
        String nombreNorm = normalizarAcentos(nombre != null ? nombre : "");
        String combined = normalizarAcentos((raw != null ? raw : "") + " " + (nombre != null ? nombre : ""));

        // Infantil primero: "niños"/"niñas" no debe perderse contra ningún otro
        // match (gym armador los excluye explícitamente — no son adultos).
        if (combined.contains("nino") || combined.contains("nina") ||
            combined.contains("kids") || combined.contains("infantil") ||
            combined.contains("bebe")) return "infantil";

        // Señal explícita "de hombre"/"de mujer" en el NOMBRE del producto gana
        // sobre un spec de género del sitio (raw) que puede estar mal taggeado
        // a nivel catálogo — bug encontrado en vivo: "Calza Nike One De Mujer"
        // (Sporting) traía raw="Hombre" del spec VTEX y el combined check de
        // abajo (que mira raw+nombre con "hombre" primero) lo pisaba, mostrando
        // una calza de mujer en outfits de hombre.
        if (nombreNorm.contains("de mujer") || nombreNorm.contains("para mujer")) return "mujer";
        if (nombreNorm.contains("de hombre") || nombreNorm.contains("para hombre")) return "hombre";

        // Feminine-coded category override: si la categoría es inherentemente femenina
        // Y el nombre del producto no tiene señal masculina explícita, forzar "mujer"
        // ANTES de que combined.contains("hombre") lo pise con el raw VTEX.
        // Cubre: Calza, Pollera, Vestido, Enterito, Corpino, Malla.
        boolean hasExplicitMascSignal = nombreNorm.contains("de hombre")
                || nombreNorm.contains("para hombre")
                || nombreNorm.contains(" hombre")
                || nombreNorm.contains("masculino")
                || nombreNorm.contains("caballero");
        if (FEMININE_CODED_CATEGORIES.contains(categoria) && !hasExplicitMascSignal) {
            return "mujer";
        }

        if (combined.contains("hombre") || combined.contains("masculino") ||
            combined.contains(" men")   || combined.contains("male")      ||
            combined.contains("caballero") || combined.contains("varones")) return "hombre";

        if (combined.contains("mujer")  || combined.contains("femenino") ||
            combined.contains("women")  || combined.contains("female")   ||
            combined.contains("dama")   || combined.contains("damas")) return "mujer";

        if (combined.contains("unisex") || combined.contains("neutro")) return "unisex";

        // Inferir por nombre de producto (modelos icónicos)
        if (combined.contains("wmns") || combined.contains("w ") ||
            combined.contains(" w)")) return "mujer";

        if (raw != null && !raw.isBlank()) return raw.trim().toLowerCase();

        // Sin ninguna señal de género (ni nombre ni spec del sitio): Calza es,
        // en este catálogo, predominantemente de mujer (80 mujer vs. un puñado
        // de hombre, todas estas últimas con tag explícito) — confirmado por el
        // usuario tras ver calzas sin género coladas en outfits de hombre.
        if ("Calza".equals(categoria)) return "mujer";

        return "";
    }

    private String normalizarAcentos(String s) {
        return AccentStripper.strip(s.toLowerCase());
    }
}
