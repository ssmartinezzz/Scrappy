package ar.scraper.aggregator.normalize;

import java.util.Map;

/**
 * Sub-category of a hand tool, named the way an Argentine ferretería does, read from the LEADING noun of the name: "Juego de
 * destornilladores" is a Destornillador, "Set de herramientas 100 piezas" a Juego y Set, "Juego de bocallaves" a Llave y Tubo. Same
 * mechanism as {@link ElectroSubcategoryResolver} ({@link LeadingNounRules}). Every rule vetoes the power-tool words, so a battery drill
 * misfiled here keeps no sub. {@code VOCABULARIO} is the text V131 backfills from, and {@code V131HerramientaAgreementTest} fails if
 * the two drift. A name no rule claims keeps {@code ""}.
 */
final class HerramientaSubcategoryResolver {

    private static final String VOCABULARIO =
        "he|Pinza y Alicate| pinza, alicate, tenaza, pelacable|| unas , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Destornillador y Puntas| destornillador, atornillador|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Destornillador y Puntas| punta , puntas | phillips, ph1, ph2, ph3, torx, atornillar, impact, hexagonal, pozidriv, destornillador| logica , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Llave y Tubo| llave , llaves || paso , luz , gas , termica , impacto , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Llave y Tubo| bocallave, tubo , tubos , dado , dados , crique , criques || dados de goma , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Martillo y Maza| martillo, maza, mazo , mazos || electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Medición| nivel , niveles , cinta metrica, metro , escuadra, medidor, calibre, regla , reglas , plomada, goniometro, tester, multimetro, analizador|| electrica , electrico , electricas , electricos , neumatic, taladro, amoladora;" +
        "he|Medición| pistola| medir , temperatura | electrica , electrico , electricas , electricos , neumatic, taladro, amoladora;" +
        "he|Albañilería| llana, fratacho, cuchara, pala , palas , pico , ventosa, balde , baldes || electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Espátula y Rasqueta| espatula, rasqueta|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Corte y Limado| serrucho, arco , sierra , tijera, cutter, hacha, formon, lima , limas , corta, cincel, machete, cuchilla, cuchillo, navaja, gubia, corte || fresa , rotativa , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Corte y Limado| hoja , hojas | sierra , cutter| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Corte y Limado| repuesto , repuestos | cutter| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Corte y Limado| cepillo , cepillos | carpintero| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Prensa y Morsa| prensa, morsa, morza, sargento, mordaza|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Pistola de Silicona| pistola| aplicadora, calafatera, silicona, cartucho| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Engrampadora y Remachadora| engrampadora, engrapadora, grampadora, remachadora|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Juego y Set| herramientas , maletin , gabinete , valija , estuche || electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;" +
        "he|Juego y Set| caja , cajas | herramientas , set , kit , piezas | inglete , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora";

    // "juego" is skipped like "set" and "kit", and so are the piece count and "completo": the tool that follows names the sub.
    static final LeadingNounRules REGLAS = new LeadingNounRules(VOCABULARIO, Map.of("Herramienta Manual", "he"), "juego|piezas|pza|pzs|pz|completo");
}
