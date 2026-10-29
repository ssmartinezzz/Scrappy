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

    private static final Set<String> WOMEN_TOKENS =
            Set.of(" wmn ", " girl ", " croptop ", " crop top ", " bras ");

    // Whole words bounded by non-[a-z0-9] (not \b: it misreads accented text), plus "crop top"
    // split by exactly one space. Same rule as V58.
    private static final Set<String> NAME_WOMEN = Set.of("wmn", "girl", "croptop", "bras", "woman", "women",
            "mujer", "mujeres", "dama", "damas", "femenino", "calza", "calzas");
    private static final Set<String> NAME_MEN = Set.of("hombre", "hombres", "men", "mens", "masculino",
            "caballero", "caballeros", "varon", "varones");

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

        // The name beats the site tag: collections (e.g. Monkyforce) tag women's garments as hombre/unisex.
        if (nombreDiceMujer(nombreNorm)) return "mujer";

        if (combined.contains("hombre") || combined.contains("masculino") ||
            combined.contains(" men")   || combined.contains("male")      ||
            combined.contains("caballero") || combined.contains("varones")) return "hombre";

        if (combined.contains("mujer")  || combined.contains("femenino") ||
            combined.contains("women")  || combined.contains("female")   ||
            combined.contains("dama")   || combined.contains("damas")) return "mujer";

        String padded = " " + combined + " ";
        if (WOMEN_TOKENS.stream().anyMatch(padded::contains)) return "mujer";

        if (combined.contains("unisex") || combined.contains("neutro")) return "unisex";

        if (combined.contains("wmns") || combined.contains("w ") ||
            combined.contains(" w)")) return "mujer";

        if (StringUtils.isNotBlank(raw)) return raw.trim().toLowerCase();

        // Sin ninguna señal de género (ni nombre ni spec del sitio):
        if ("Calza".equals(categoria)) return "mujer";

        return "";
    }

    /** One pass over the name's words: a women word and no men word. */
    private static boolean nombreDiceMujer(String n) {
        boolean mujer = false;
        String prev = null;
        int prevEnd = -2;
        int i = 0;
        while (i < n.length()) {
            if (!esAlfanumerico(n.charAt(i))) { i++; continue; }
            int inicio = i;
            while (i < n.length() && esAlfanumerico(n.charAt(i))) i++;
            String w = n.substring(inicio, i);
            if (NAME_MEN.contains(w)) return false;
            if (NAME_WOMEN.contains(w)
                    || ("top".equals(w) && "crop".equals(prev) && inicio == prevEnd + 1 && n.charAt(prevEnd) == ' ')) {
                mujer = true;
            }
            prev = w;
            prevEnd = i;
        }
        return mujer;
    }

    private static boolean esAlfanumerico(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private String normalizarAcentos(String s) {
        return AccentStripper.strip(s.toLowerCase());
    }
}
