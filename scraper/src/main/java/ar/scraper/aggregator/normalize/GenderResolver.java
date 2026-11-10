package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/** Gender resolution with feminine-coded category override + infantil guard. */
@Component
public class GenderResolver {

    // Cuando una de estas categorias aparece SIN señal masculina explícita en el nombre del
    // producto, el género se fuerza a "mujer" antes de que el combined-check (que incluye raw VTEX)
    // lo pise.
    private static final Set<String> FEMININE_CODED_CATEGORIES =
            Set.of("Calza", "Pollera", "Vestido", "Enterito", "Corpino", "Bombacha", "Malla");

    private static final Set<String> WOMEN_TOKENS =
            Set.of(" wmn ", " girl ", " croptop ", " crop top ", " bras ");

    // Whole words bounded by non-[a-z0-9] (not \b: it misreads accented text), plus "crop top"
    // split by exactly one space. Same rule as V58.
    private static final Set<String> NAME_WOMEN = Set.of("wmn", "girl", "croptop", "bras", "woman", "women",
            "mujer", "mujeres", "dama", "damas", "femenino", "calza", "calzas");
    private static final Set<String> NAME_MEN = Set.of("hombre", "hombres", "men", "mens", "masculino",
            "caballero", "caballeros", "varon", "varones");

    // Kids' size labels. Whole words only: "juniors" (Boca Juniors) must not match "junior". Same rule as V60.
    private static final Set<String> KIDS_TOKENS = Set.of(" jr ", " junior ", " kid ", " kids ", " youth ",
            " ps ", " gs ", " td ", " toddler ");
    // Whole words too: as substrings they hit teaNINA, argiNINA, alaNINA, femeNINO and BEBEr.
    private static final Set<String> KIDS_WORDS = Set.of(" nino ", " nina ", " ninos ", " ninas ", " bebe ",
            " bebes ", " infantil ", " infantiles ", " kids ");
    // Kids' name signals with no size label. Whole words, accent-stripped, like the sets above, and V81.
    // Footy is a kids-only brand, so it holds in any rubro. The rest only in clothing: "escolar" is also
    // stationery and "Peppa Pig" is also a pet toy. Deliberately not here: Marvel, Spider-Man, Disney and
    // Hello Kitty (adult adidas/Crocs collabs, Hadriatica collectibles), Stitch (Dr. Martens "stitch"
    // leather), Barbie (Hadriatica Nintendo dress).
    private static final Set<String> KIDS_BRAND_WORDS = Set.of(" footy ");
    private static final Set<String> KIDS_NAME_WORDS = Set.of(" juvenil ", " juveniles ", " escolar ",
            " escolares ", " minnie ", " mickey ", " paw patrol ", " peppa ", " frozen ", " toy story ");
    private static final Pattern NO_ALNUM = Pattern.compile("[^a-z0-9]+");

    public String resolver(String raw, String nombre, String categoria) {
        return resolver(raw, nombre, categoria, "");
    }

    /** Blank rubro counts as clothing, as everywhere else in the normalizer. */
    public String resolver(String raw, String nombre, String categoria, String rubro) {
        String nombreNorm = normalizarAcentos(nombre != null ? nombre : "");
        String combined = normalizarAcentos((raw != null ? raw : "") + " " + (nombre != null ? nombre : ""));

        // "niños"/"niñas" no debe perderse contra ningún otro match (gym armador los excluye
        // explícitamente — no son adultos).
        String palabras = " " + NO_ALNUM.matcher(combined).replaceAll(" ").trim() + " ";
        if (KIDS_WORDS.stream().anyMatch(palabras::contains)) return "infantil";
        // Outside clothing the same tokens are not sizes: "450 GS" is grams, "PC/PS" is PlayStation.
        boolean esRopa = rubro == null || rubro.isBlank() || "indumentaria".equals(rubro);
        if (esRopa && KIDS_TOKENS.stream().anyMatch(palabras::contains)) return "infantil";
        if (KIDS_BRAND_WORDS.stream().anyMatch(palabras::contains)) return "infantil";
        if (esRopa && KIDS_NAME_WORDS.stream().anyMatch(palabras::contains)) return "infantil";

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

        // The short markers are whole words: as substrings "men" hit MENdoza, "male" hit feMALE and
        // "dama" hit DAMAsco.
        if (combined.contains("hombre") || combined.contains("masculino") ||
            palabras.contains(" men ")  || palabras.contains(" mens ")    || palabras.contains(" male ") ||
            combined.contains("caballero") || combined.contains("varones")) return "hombre";

        if (combined.contains("mujer")  || combined.contains("femenino") ||
            combined.contains("women")  || palabras.contains(" female ") ||
            palabras.contains(" dama ") || palabras.contains(" damas ")) return "mujer";

        String padded = " " + combined + " ";
        if (WOMEN_TOKENS.stream().anyMatch(padded::contains)) return "mujer";

        if (combined.contains("unisex") || combined.contains("neutro")) return "unisex";

        // "w " as a substring tagged neW Era, sNOW and New Balance as mujer; the marker is the word W.
        if (combined.contains("wmns") || palabras.contains(" w ")) return "mujer";

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
