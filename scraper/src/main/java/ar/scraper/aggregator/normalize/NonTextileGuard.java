package ar.scraper.aggregator.normalize;

import org.apache.commons.lang3.StringUtils;

/** Early-exit "clearly not textile" predicate. */
public final class NonTextileGuard {

    private NonTextileGuard() {}

    /** Primera palabra(s) que indican que el producto NO es indumentaria/calzado. */
    private static final String[] NO_TEXTIL_INICIO = {
        // El guard existe para que un producto no-textil no se clasifique como ROPA, no para
        // dejarlo sin clasificar cuando ya tiene dónde ir.
        "ball ","palo ","stick ","raqueta","bate ","arco ",
        // "Mouse Logitech M110 Silent Red" y "Cable de Red RJ-45 2M" entran los dos en los primeros
        // 35 caracteres que este guard mira, así que un mouse rojo y un cable de red quedaban sin
        // clasificar.
        "red de ","valla ","cono ","bolsa deportiva","costurero",
        "guantes boxing","guantes portero","casco bici","casco skate",
        "cadena ","collar ","pulsera ","anillo ","aros ","aro ","colgante ",
        "brazalete ","tobillera joya","piercing","broche ",
        "maletin","valija","paraguas","bastón","baston ","cinturon portaherramientas",
        "crema ","locion ","loción ","gel ","shampoo","jabon ","jabón ","protector solar",
    };

    /** Solo revisa las primeras 3 palabras para no sobrebloquear. */
    public static boolean esClaramenteNoTextil(String texto) {
        if (StringUtils.isBlank(texto)) return false;
        String lower = texto.toLowerCase()
            .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
            .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
            .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
        // Solo mirar inicio (primeras 3 palabras = ~25 chars)
        String inicio = lower.length() > 35 ? lower.substring(0, 35) : lower;
        for (String kw : NO_TEXTIL_INICIO) {
            String trimmed = kw.trim();
            // Word-boundary match on BOTH sides: avoids false positives like "Asics Gel-Kayano"
            // matching the cosmetic "gel " filter via a bare " gel" substring (no closing boundary)
            // — a real false-positive surfaced by the KW_RUNNING_MODELO regression test (Issue 3).
            if (inicio.startsWith(trimmed + " ") || inicio.equals(trimmed)
                    || inicio.contains(" " + trimmed + " ")) {
                return true;
            }
        }
        return false;
    }
}
