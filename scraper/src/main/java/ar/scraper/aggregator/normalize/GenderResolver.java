package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.springframework.stereotype.Component;

import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** Gender resolution with feminine-coded category override + infantil guard. */
@Component
public class GenderResolver {

    // Cuando una de estas categorias aparece SIN señal masculina explícita en el nombre del
    // producto, el género se fuerza a "mujer" antes de que el combined-check (que incluye raw VTEX)
    // lo pise.
    private static final Set<String> FEMININE_CODED_CATEGORIES =
            Set.of("Calza", "Pollera", "Vestido", "Enterito", "Corpino", "Malla");

    public String resolver(String raw, String nombre, String categoria) {
        String nombreNorm = normalizarAcentos(nombre != null ? nombre : "");
        String combined = normalizarAcentos((raw != null ? raw : "") + " " + (nombre != null ? nombre : ""));

        // "niños"/"niñas" no debe perderse contra ningún otro match (gym armador los excluye
        // explícitamente — no son adultos).
        if (combined.contains("nino") || combined.contains("nina") ||
            combined.contains("kids") || combined.contains("infantil") ||
            combined.contains("bebe")) return "infantil";

        // Señal explícita "de hombre"/"de mujer" en el NOMBRE del producto gana sobre un spec de
        // género del sitio (raw) que puede estar mal taggeado a nivel catálogo — bug encontrado en
        // vivo:
        if (nombreNorm.contains("de mujer") || nombreNorm.contains("para mujer")) return "mujer";
        if (nombreNorm.contains("de hombre") || nombreNorm.contains("para hombre")) return "hombre";

        // Feminine-coded category override: si la categoría es inherentemente femenina Y el nombre
        // del producto no tiene señal masculina explícita, forzar "mujer" ANTES de que
        // combined.contains("hombre") lo pise con el raw VTEX.
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

        if (combined.contains("wmns") || combined.contains("w ") ||
            combined.contains(" w)")) return "mujer";

        if (StringUtils.isNotBlank(raw)) return raw.trim().toLowerCase();

        // Sin ninguna señal de género (ni nombre ni spec del sitio):
        if ("Calza".equals(categoria)) return "mujer";

        return "";
    }

    private String normalizarAcentos(String s) {
        return AccentStripper.strip(s.toLowerCase());
    }
}
