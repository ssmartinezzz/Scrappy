package ar.scraper.aggregator.normalize;

import java.util.Map;

/**
 * Sub-category of a bicycle: the BIKE TYPE, in the words Argentine stores use (Mountain Bike, Paseo y Playera, Urbana / Fixie...).
 * Every name leads with bicicleta, so the type comes from an explicit word in the name; only when there is none does the RODADO decide
 * ("R29" is a Mountain Bike, "R12" a kid's bike). An exercise bike (fija, spinning, indoor) is not a bike type: the rule that claims it
 * returns {@code ""} so that no later rule gives it one. Same mechanism as {@link ElectroSubcategoryResolver}
 * ({@link LeadingNounRules}: the leader is the bike, {@code any} the type or the rodado). {@code VOCABULARIO} is the text V132 backfills
 * from, and {@code V132BicicletaAgreementTest} fails if the two drift. A name no rule claims keeps {@code ""}.
 */
final class BicicletaSubcategoryResolver {

    private static final String VOCABULARIO =
        "bi|Camicleta| camicleta||;" +
        "bi|Camicleta| bicicleta, bici , bicis | sin pedales , equilibrio , balance bike |;" +
        "bi|Triciclo| triciclo||;" +
        "bi|Eléctrica| bicicleta, bici , bicis | electrica , electrico |;" +
        "bi|Plegable| bicicleta, bici , bicis | plegable|;" +
        "bi|BMX| bicicleta, bici , bicis | bmx , freestyle , stunt , dirt jump |;" +
        "bi|| bicicleta, bici , bicis | fija , spinning , indoor , estatica , magnetica , horizontal , pedalera |;" +
        "bi|Infantil| bicicleta, bici , bicis | infantil , infanti , nena , nene , nina , nino, kids , rueditas , bebe, junior |;" +
        "bi|Infantil| bicicleta, bici , bicis | r12, r 12, rodado 12, rod 12, rod. 12, rod12, r13, r 13, rodado 13, rod 13, rod. 13, rod13, r14, r 14, rodado 14, rod 14, rod. 14, rod14, r16, r 16, rodado 16, rod 16, rod. 16, rod16, bicicleta 12, bicicleta 14, bicicleta 16|;" +
        "bi|Ruta| bicicleta, bici , bicis | ruta , carrera , speed , gravel , 700c, ciclocross |;" +
        "bi|Urbana / Fixie| bicicleta, bici , bicis | urbana, urban , fixie, fixed , ciudad |;" +
        "bi|Mountain Bike| bicicleta, bici , bicis | mtb , mountain , moutain , montana , fat bike |;" +
        "bi|Paseo y Playera| bicicleta, bici , bicis | playera, paseo, vintage , classic, retro , city |;" +
        "bi|Mountain Bike| bicicleta, bici , bicis | r29, r 29, rodado 29, rod 29, rod. 29, rod29, r27, r 27, rodado 27, rod 27, rod. 27, rod27, bicicleta 29, bicicleta 27|;" +
        "bi|Infantil| bicicleta, bici , bicis | r18, r 18, rodado 18, rod 18, rod. 18, rod18, r20, r 20, rodado 20, rod 20, rod. 20, rod20, bicicleta 20| dama , lady , mujer , adulto , hombre , varon , plegable";

    static final LeadingNounRules REGLAS = new LeadingNounRules(VOCABULARIO, Map.of("Bicicleta", "bi"), "");
}
