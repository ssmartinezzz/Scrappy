package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * What is sold for a baby: strollers, cots, playpens, bottles, pacifiers, bibs, diapers, walkers, car seats, sterilizers, breast
 * pumps, baby monitors. It runs LAST in the name families, so it only names what no family claimed (a toy rattle stays a
 * {@code Juguete}, a baby-bed mattress a {@code Colchón}); the one exception is {@link #esMonitorDeBebe}, which runs first
 * because a baby monitor reads as a {@code Monitor} or a {@code Cámara}. A name is a baby item when it LEADS with the piece, or
 * carries the name of a baby-care maker: a doll ("Bebote", "Bebé Muñeca Reborn") is a toy and a pet bed is a pet's.
 */
final class BebeClassifier implements FamiliaClassifier {

    static final String CATEGORIA = "Artículo de Bebé";

    private static final String PREFIJO = "(?:(?:kit|set|pack|combo|outlet|x|de|por|[0-9]+|x[0-9]+) )*";

    private static final Pattern PIEZA = Pattern.compile("^ " + PREFIJO
            + "(?:cochecit[oa]s?|practicunas?|corralit[oa]s?|cunas?|cuneros?|moises|mamaderas?|biberon(?:es)?|chupetes?|baberos?"
            + "|panal(?:es)?|panaleras?|cambiador(?:es)?|portabebes?|andador(?:es)?|bouncers?|pata pata|huevit[oa]s?|boosters?"
            + "|gimnasios? (?:de |para )?bebes?|butacas? (?:de |para )?auto|sillas? (?:de |para )auto"
            + "|esterilizador(?:es)? (?:de |para )?(?:mamaderas?|biberon(?:es)?)|calentador(?:es)? de (?:mamaderas?|biberon(?:es)?|toallitas)"
            + "|sacaleches?|extractor(?:es)? de leche|aspirador(?:es)? nasal(?:es)?|tira de cuna|protector(?:es)? de cuna|movil de cuna"
            + "|puertas? de seguridad|barreras? de seguridad|rejas? de seguridad|set de regalo|monitor(?:es)? (?:para |de )?bebes?) ");
    /**
     * The nouns that name a baby item whatever else the name says: a "Cochecito Oxford" is not a shirt and a "Cochecito Bebesit Luggy"
     * is not a boot, which is how the clothing families read them. They run before the families, like the baby monitor.
     */
    private static final Pattern FIRME = Pattern.compile("^ " + PREFIJO
            + "(?:cochecit[oa]s?|practicunas?|mamaderas?|biberon(?:es)?|chupetes?|baberos?|panal(?:es)?|sacaleches?"
            + "|extractor(?:es)? de leche|esterilizador(?:es)? (?:de |para )?(?:mamaderas?|biberon(?:es)?)"
            + "|calentador(?:es)? de (?:mamaderas?|biberon(?:es)?)|andador(?:es)?|cunas?|cuneros?|huevit[oa]s?"
            + "|butacas? (?:de |para )?(?:auto|booster|bebes?)|boosters? (?:para )?(?:auto|bebes?)) ");
    private static final Pattern PARA_COCHECITO_O_CHUPETE = Pattern.compile(" (?:para|de|del) (?:cochecit[oa]s?|chupetes?|mamaderas?) ");
    /** "Coche" alone is a car: it needs a stroller word. */
    private static final Pattern COCHE = Pattern.compile("^ " + PREFIJO + "coches? ");
    private static final Pattern COCHE_DE_BEBE = Pattern.compile(
            " (?:bebes?|bebesit|travel system|paraguita|paseo|capota|infanti|cabin|maxi cosi|moises|huevito|quinny) ");
    private static final Pattern BANERA = Pattern.compile("^ " + PREFIJO + "baneras? (?:[a-z0-9]+ )*(?:bebes?|bebesit|infantil|ninos?) ");
    private static final Pattern CUIDADO_DE_BEBE = Pattern.compile("^ " + PREFIJO
            + "(?:regalos?|completo|higiene|cuidados?|accesorios?|cepillo|peine|manicura|set de higiene) (?:[a-z0-9]+ ){0,6}(?:para |del? |y )?bebes? ");
    /** What only a baby item names, wherever it sits in the name ("Nonino Apósito Refuerza Pañal", "Tetinas NaturalWave"). */
    private static final Pattern SENAL_DE_BEBE = Pattern.compile(
            " (?:tetinas?|lactancia|mamaderas?|sacaleches?|biberon(?:es)?|porta chupetes?|set de alimentacion) ");
    private static final Pattern MARCA_DE_BEBE = Pattern.compile(
            " (?:bebesit|maxi cosi|safety 1st|avent|lansinoh|momcozy|babysec|pampers|huggies|mawe|sebigus) ");
    private static final Pattern NO_ES_DE_BEBE = Pattern.compile(
            " (?:bebotes?|munecas?|reborns?|baby alive|peluche|peluches|mascotas?|perros?|gatos?|adultos?|incontinencia|ortopedic[oa]s?"
            + "|rollator|ancianos|geriatric[oa]s?|album|libro|cuaderno|torta|globos?|cumpleanos|disfraz|canilla|grifo|griferia|canerias?|bicicletas?|camicletas?|monopatin|scooter) ");
    private static final Pattern MONITOR = Pattern.compile(
            " (?:baby ?call|baby monitor|monitor (?:para |de )?bebes?|monitor baby|bebes? monitor) ");

    @Override
    public String clasificar(TextoClasificable texto) {
        String t = GamingReglas.tokens(texto.normalizado());
        return esDeBebe(t) ? CATEGORIA : "";
    }

    /**
     * A baby monitor or baby call (it beats the {@code Monitor} and {@code Cámara} families that read the same words) or a name
     * that leads with a stroller, bottle, pacifier, bib, diaper, walker or cot (it beats the clothing families).
     */
    static boolean reclamaAntesDeLasFamilias(TextoClasificable texto) {
        String t = GamingReglas.tokens(texto.normalizado());
        if (MONITOR.matcher(t).find()) return true;
        if (NO_ES_DE_BEBE.matcher(t).find()) return false;
        return FIRME.matcher(t).find() || PARA_COCHECITO_O_CHUPETE.matcher(t).find();
    }

    private static boolean esDeBebe(String t) {
        if (NO_ES_DE_BEBE.matcher(t).find()) return false;
        if (PIEZA.matcher(t).find() || CUIDADO_DE_BEBE.matcher(t).find() || BANERA.matcher(t).find()) return true;
        if (COCHE.matcher(t).find() && COCHE_DE_BEBE.matcher(t).find()) return true;
        return MARCA_DE_BEBE.matcher(t).find() || SENAL_DE_BEBE.matcher(t).find();
    }
}
