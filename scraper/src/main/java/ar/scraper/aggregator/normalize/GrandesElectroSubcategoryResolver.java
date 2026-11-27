package ar.scraper.aggregator.normalize;

import java.util.Map;

/**
 * Sub-category of a washing machine, cooker, fridge, heater or water heater, in the words Argentine retail uses (a Lavavajillas
 * and a Secarropas are not a Lavarropas; a Caloventor and a Tiro Balanceado are not the same heater). The category was already
 * decided, so most rules look for the noun ANYWHERE in the name (the leader {@code " "} matches every name): "Carga Superior
 * Lavarropas Digital Inverter" and "Bespoke AI Freezer Inferior Heladera" do not lead with it. The first row that fires wins, so
 * the order is the precedence: a "Heladera con freezer" is a Heladera, a "Panel Convector" a Convector. A name no rule claims
 * (a fireplace, a salamander, a tiro natural heater) keeps {@code ""}. Same mechanism as {@link ElectroSubcategoryResolver}
 * ({@link LeadingNounRules}); {@code VOCABULARIO} is the text V144 backfills from, and {@code V144GrandesElectroAgreementTest}
 * fails if the two drift.
 */
final class GrandesElectroSubcategoryResolver {

    private static final String VOCABULARIO =
        "la|Secarropas| secarropas , secadora , centrifuga , centrifugo ;" +
        "la|Lavavajillas| | lavavajillas , lavavajilla , lavaplatos ;" +
        "la|Lavarropas| | lavarropas , lavarropa , lavasecarropas , lavasecarropa , lavaseca , lavasecas , lavadora , carga frontal , carga superior , semiautomatico , semiautomatica ;" +
        "la|Secarropas| | secarropas , secadora , centrifuga , centrifugo ;" +
        "co|Microondas| | microondas , microonda ;" +
        "co|Anafe| anafe , anafes ;" +
        "co|Cocina| cocina , cocinas ;" +
        "co|Horno| | horno , hornos , hornito , hornitos ;" +
        "co|Anafe| | anafe , anafes ;" +
        "co|Cocina| | cocina , cocinas ;" +
        "he|Exhibidora| | exhibidora , exhibidoras , exhibidor , visicooler ;" +
        "he|Cava| | cava , cavas , vinoteca , vinotecas ;" +
        "he|Heladera| | heladera , heladeras , refrigerador , refrigeradora , frigobar , side by side , nevera , combi ;" +
        "he|Freezer| | freezer , freezers , congelador , congeladora ;" +
        "ca|Tiro Balanceado| | tiro balanceado , tiraje balanceado , tbu , tb ;" +
        "ca|Sin Salida| | sin salida , s salida , ss ;" +
        "ca|Caloventor| | caloventor , caloventores , turbocalefactor , termoventilador ;" +
        "ca|Convector| | convector , convectores ;" +
        "ca|Panel| | panel , paneles ;" +
        "ca|Radiador| | radiador , radiadores , oleoelectrico , oleo electrico ;" +
        "ca|Estufa| | estufa , estufas , cuarzo , infrarroja , infrarrojo , halogena , halogeno , garrafera , exterior , patio ;" +
        "te|A Gas| | gas , multigas , kcal , gn , ge , natural , envasado ;" +
        "te|Eléctrico| | electrico , electrica , electr , electr. ";

    static final LeadingNounRules REGLAS = new LeadingNounRules(VOCABULARIO,
        Map.of("Lavarropas", "la", "Cocina y Horno", "co", "Heladera", "he", "Calefactor", "ca", "Termotanque", "te"), "");
}
