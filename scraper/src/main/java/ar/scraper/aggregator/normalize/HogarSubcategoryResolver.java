package ar.scraper.aggregator.normalize;

import java.util.Map;

/**
 * Sub-category of a light or a piece of furniture, in the words Argentine retail uses, read from the LEADING noun of the name:
 * "Lámpara Colgante" is a Colgante, "Lámpara E27 9 W" a Lamparita (the bulb), "Mueble Para Tv" a Rack de TV and a "Combo de Cocina"
 * a Cocina Modular. Same mechanism as {@link ElectroSubcategoryResolver} ({@link LeadingNounRules}); {@code VOCABULARIO} is the text
 * V129 backfills from, and {@code V129HogarAgreementTest} fails if the two drift. A name no rule claims keeps {@code ""}.
 */
final class HogarSubcategoryResolver {

    private static final String ILUMINACION = "Iluminación";
    private static final String MUEBLE = "Mueble";
    private static final String VOCABULARIO =
        "il|Lámpara de Pie| lampara , lamp , luz , luminaria , led | de pie , pie | escritorio , mesa ;" +
        "il|Colgante| colgante , colgantes , arana , aranas ;" +
        "il|Colgante| lampara , lamp , luminaria | colgante , colgar ;" +
        "il|Aplique| aplique , apliques , tortuga , tortugas ;" +
        "il|Aplique| lampara , lamp , luminaria | pared , aplique | pie ;" +
        "il|Velador| velador , veladores ;" +
        "il|Velador| lampara , lamp , luminaria | escritorio , escr , esc , mesa , velador , noche , lectura , pinza , cabecera , infantil ;" +
        "il|Velador| luz | velador , noche ;" +
        "il|Spot| spot , spots , dicrospot , cabezal , cabezales , riel , rieles , dicro ;" +
        "il|Spot| lampara , lamp | riel ;" +
        "il|Spot| difusor , difusores | gu10 ;" +
        "il|Plafón| plafon , plafones , plafoniere ;" +
        "il|Plafón| led , luz , lampara , luminaria | plafon , plafones ;" +
        "il|Reflector| reflector , reflectores , reflectora , reflectoras , proyector , proyectores || planetario , galaxia , estrellas , xgimi , astronauta , sunset , atardecer , laser , epson , ansi , hdmi ;" +
        "il|Reflector| led , luz | reflector , reflectores , reflectora , proyector , proyectores | e27 , e14 , cultivo ;" +
        "il|Panel y Listón| panel , paneles , liston , listones , tubo , tubos || matrix , auto , camioneta , solar ;" +
        "il|Tira LED| tira , tiras , cinta led , cintas led , manguera led , manguera luminosa ;" +
        "il|Guirnalda| guirnalda , guirnaldas , cortina , cortinas , serie de luces , cadena de luces ;" +
        "il|Farol y Estaca| farol , faroles , farola , farolas , estaca , estacas , baliza , balizas , luz tipo farol ;" +
        "il|Luz de Emergencia| luz emergencia , luz de emergencia , luces de emergencia , lampara de emergencia , lampara emergencia , luminaria de emergencia , luminaria emergencia , emergencia ;" +
        "il|Portalámpara| portalampara , portalamparas , porta lampara , floron , florones ;" +
        "il|Lamparita| lamparita , lamparitas , bombilla , bombillas , bulbo , bulbos , dicroica , dicroicas , ar111 , par38 , par30 ;" +
        "il|Lamparita| lampara , lamparas , lamp | e27 , e14 , gu10 , g9 , gu5 , g4 , r7s , b22 , mr16 , dicroica , filamento , bulbo , globo , vela , t10 , t8 , 12v , smart , foco , focos ;" +
        "il|Lamparita| luz | bulbo , e27 , gu10 , dicroica , filamento ;" +
        "il|Lamparita| led || lineal , difuso , panel , tira , spot ;" +
        "mu|Cocina Modular| cocina , cocinas , muebles de cocina , mueble de cocina, muebles cocina , mueble cocina , completo de cocina ;" +
        "mu|Bajo Mesada| bajo mesada, bajos mesada , mueble bajo mesada, modulo bajo mesada, modulo bajomesada , muebles bajo mesada, mueble de bajo mesada , juego de bajo mesada , bajomesada ;" +
        "mu|Alacena| alacena , alacenas , mueble alacena , armario aereo , modulo alacena ;" +
        "mu|Despensero| despensero , despensera , despenseros , mueble despensero ;" +
        "mu|Despensero| organizador , armario , escobero | despensero ;" +
        "mu|Portahorno| portahorno , portahornos , mueble para microondas , muebles para microondas , mueble porta microondas , mueble para horno , porta hornos , porta horno , porta microondas ;" +
        "mu|Rack de TV| rack , racks , panel rack , panel de tv , panel tv , mesa rack , mesa tv , mesa para tv , mueble para tv , mueble tv , mueble rack , mueble de tv , centro de entretenimiento ;" +
        "mu|Biblioteca| biblioteca , bibliotecas , librero , libreros , mueble biblioteca ;" +
        "mu|Cómoda y Cajonera| comoda , comodas , cajonera , cajoneras , cajonero , mueble comoda ;" +
        "mu|Chifonier| chifonier , chiffonier , chifonnier , sifonier ;" +
        "mu|Vajillero| vajillero , vajilleros , cristalero , cristaleros , mueble vajillero ;" +
        "mu|Aparador| aparador , aparadores , buffet , bahiut , bahut , credenza , mueble aparador ;" +
        "mu|Mesa de Luz| mesa de luz , mesas de luz , mesita de luz , mesita de noche , mesa de noche , mesa luz , mesa mesita de luz , panel con mesa de luz , mesita , velador ;" +
        "mu|Zapatero| zapatero , zapateros , zapatera , botinero , botineros , organizador de zapatos , organizador de calzado , organizador zapatero , armario zapatero ;" +
        "mu|Rack de TV| mueble , muebles | rack tv , rack de tv , para tv ;" +
        "mu|Zapatero| mueble , muebles | zapatero , botinero | esquinero ;" +
        "mu|Cocina Modular| modular , modulo | cocina ";

    // "hue" so that "Philips Hue Plafón" leads with "plafon"; V129 skips the same two words.
    static final LeadingNounRules REGLAS = new LeadingNounRules(VOCABULARIO,
        Map.of(ILUMINACION, "il", MUEBLE, "mu"), "hue|philips");
}
