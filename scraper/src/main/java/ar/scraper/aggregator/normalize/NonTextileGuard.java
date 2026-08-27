package ar.scraper.aggregator.normalize;

/**
 * Early-exit "clearly not textile" predicate.
 *
 * <p>Extracted from {@code NormalizerService.esClaramenteNoTextil} +
 * {@code NO_TEXTIL_INICIO} (Work Unit 3 of the aggregator SOLID
 * modularization) — pure relocation, no behavior change.</p>
 */
public final class NonTextileGuard {

    private NonTextileGuard() {}

    /**
     * Primera palabra(s) que indican que el producto NO es indumentaria/calzado.
     * Si el nombre empieza con estas palabras → no clasificar como ropa.
     */
    private static final String[] NO_TEXTIL_INICIO = {
        // Deportes / equipamiento
        // "pelota"/"balon" salieron de esta lista: desde richer-category-taxonomy
        // tienen categoría propia (Pelota). Frenarlos acá devolvía "" y los
        // mandaba a Otros — 89 filas activas. El guard existe para que un
        // producto no-textil no se clasifique como ROPA, no para dejarlo sin
        // clasificar cuando ya tiene dónde ir.
        "ball ","palo ","stick ","raqueta","bate ","arco ",
        // "red de " y no "red " pelado: la forma corta bloqueaba por COLOR.
        // "Mouse Logitech M110 Silent Red" y "Cable de Red RJ-45 2M" entran los
        // dos en los primeros 35 caracteres que este guard mira, así que un
        // mouse rojo y un cable de red quedaban sin clasificar. En el catálogo
        // no hay una sola red deportiva; la forma larga la sigue cubriendo.
        "red de ","valla ","cono ","bolsa deportiva","costurero",
        "guantes boxing","guantes portero","casco bici","casco skate",
        // Joyería / bijouterie
        "cadena ","collar ","pulsera ","anillo ","aros ","aro ","colgante ",
        "brazalete ","tobillera joya","piercing","broche ",
        // Accesorios no-ropa
        "maletin","valija","paraguas","bastón","baston ","cinturon portaherramientas",
        // Cosméticos / higiene (perfume/colonia/desodorante ahora tienen categoría propia)
        "crema ","locion ","loción ","gel ","shampoo","jabon ","jabón ","protector solar",
        // Equipos / electrónica — la sección quedó VACÍA a propósito.
        // Tenía "router ", "teclado mecanico", "mouse gamer", "monitor led" y
        // "fuente atx". Los cinco tienen categoría tech propia desde
        // richer-category-taxonomy, y el bloque TECH corre antes que el de
        // ropa: el guard ya no los protegía de nada, sólo les bloqueaba la
        // clasificación correcta. Un "Router Wireless Mercusys" salía de acá
        // con "" y terminaba en Otros.
    };

    /**
     * Verifica si el nombre indica claramente un producto NO textil.
     * Solo revisa las primeras 3 palabras para no sobrebloquear.
     */
    public static boolean esClaramenteNoTextil(String texto) {
        if (texto == null || texto.isBlank()) return false;
        String lower = texto.toLowerCase()
            .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
            .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
            .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
        // Solo mirar inicio (primeras 3 palabras = ~25 chars)
        String inicio = lower.length() > 35 ? lower.substring(0, 35) : lower;
        for (String kw : NO_TEXTIL_INICIO) {
            String trimmed = kw.trim();
            // Word-boundary match on BOTH sides: avoids false positives like
            // "Asics Gel-Kayano" matching the cosmetic "gel " filter via a bare
            // " gel" substring (no closing boundary) — a real false-positive
            // surfaced by the KW_RUNNING_MODELO regression test (Issue 3).
            if (inicio.startsWith(trimmed + " ") || inicio.equals(trimmed)
                    || inicio.contains(" " + trimmed + " ")) {
                return true;
            }
        }
        return false;
    }
}
