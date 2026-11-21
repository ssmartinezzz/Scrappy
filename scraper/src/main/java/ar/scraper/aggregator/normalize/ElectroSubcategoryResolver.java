package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sub-category of a small appliance or a TV / audio product, read from the LEADING noun of the name: "Soporte para Smart TV" is a
 * Soporte and "Accesorio para cafetera" an Accesorio y Repuesto, never a Cafetera. The first rule that fires wins; a name no rule
 * claims keeps {@code ""}. {@code VOCABULARIO} is the text V128 backfills from (rows {@code cat|sub|leaders|any|veto}, words padded
 * with spaces); {@code V128ElectroTvAudioAgreementTest} fails if the two drift.
 */
final class ElectroSubcategoryResolver {

    private static final String PEQUENO_ELECTRO = "Pequeño Electrodoméstico";
    private static final String TV_Y_AUDIO = "TV y Audio";
    private static final Map<String, String> CLAVE = Map.of(PEQUENO_ELECTRO, "pe", TV_Y_AUDIO, "tv");

    private static final String VOCABULARIO =
        "pe|Accesorio y Repuesto| accesorio , accesorios , repuesto , repuestos , filtro , filtros , tapa , cuchilla , cuchillas , boquilla , deposito , anaquel , estante , cajon , embudo , cilindro , guia , funda , clip , recambio , consumibles , juego de trapos , jarra de vidrio ;" +
        "pe|Secador de Pelo| secador , secadores , cepillo secador , cepillo de aire || ropa , manos , unas ;" +
        "pe|Planchita y Rizador| planchita , planchitas , alisador , alisadora , alizador , alizadora , rizador , rizadora , buclera , moldeador , moldeadora , multistyler , varita , cepillo alisador , cepillo modelador , cepillo termico , cepillo multistyler , cepillo rotativo , cepillo ionico , plancha de pelo , plancha de cabello , plancha alisadora , plancha alizadora , plancha y rizadora ;" +
        "pe|Planchita y Rizador| plancha , plancha profesional | ga.ma , gama , babyliss , remington , titanium , ionic , ceramic , pulgada , pulgadas , professional , profesional , pelo , cabello , onduladora , secadora | vapor , seca , seco , vertical , asar , ropa ;" +
        "pe|Plancha de Ropa| plancha , estacion de planchado , centro de planchado | vapor , vaporizadora , seca , seco , vertical , ropa , planchado , central | pelo , cabello , asar ;" +
        "pe|Cafetera| cafetera , cafeteras , maquina de cafe , maquina para cafe , maquina de espresso , maquina espresso , maquina de expreso , maquina expreso , capsulera , bialetti , nespresso , dolce gusto ;" +
        "pe|Licuadora y Exprimidor| licuadora , licuadoras , juguera , exprimidor , exprimidora , exprimidores , blender , extractor de jugo , extractor de jugos ;" +
        "pe|Licuadora y Exprimidor| ninja | blender ;" +
        "pe|Batidora y Mixer| batidora , batidoras , mixer , minipimer , batidor , amasadora ;" +
        "pe|Procesadora| procesadora , procesadoras , procesador , picadora , multiprocesadora , robot de cocina , robot ;" +
        "pe|Pava Eléctrica| pava , pavas , hervidora , jarra electrica , jarra pava , tetera electrica , kettle ;" +
        "pe|Tostadora y Sandwichera| tostadora , tostadoras , tostador , sandwichera , sandwicheras , sanwichera , waflera , waflero , panini , paninera , grill ;" +
        "pe|Freidora| freidora , freidoras , air fryer , airfryer ;" +
        "pe|Freidora| horno | airfryer , air fryer , freidora ;" +
        "pe|Afeitadora y Cortadora| afeitadora , afeitadoras , rasuradora , maquina de afeitar , maquina para afeitar , maquinas para afeitar , maquinas de afeitar , maquinita de pelo , cortapelo , cortapelos , cortacabello , cortabarba , recortadora , cortacabellos , corta barba , corta cabello , corta cabellos , corta pelo , corte de pelo , trimmer , clipper , patillera , multigroom , oneblade , barberia ;" +
        "pe|Afeitadora y Cortadora| peluqueria , peluqueria barberia | cortadora , cortar pelo , patillera , clipper | tijera , tijeras , planchita , secador ;" +
        "pe|Afeitadora y Cortadora| cortadora , maquina de cortar , maquina para cortar , maquina cortar , maquina cortadora , maquina corta || fiambre , fiambres , alimentos , papas , verduras , queso , cesped , pasto ;" +
        "pe|Depiladora| depiladora , depiladoras , maquina depiladora , epiladora , depilador ;" +
        "tv|Soporte| soporte , soportes , bandeja para , tripode , tripodes , brazo , pie , rackera , estante , aro ;" +
        "tv|Barra de Sonido| barra de sonido , barra sonido , sound bar , soundbar , barra ;" +
        "tv|Televisor| smart , smartv , tv , televisor , televisores , led , 24\", 32\", 40\", 42\", 43\", 50\", 55\", 58\", 60\", 65\", 70\", 75\", 77\", 82\", 85\", 86\", 98\", 100\" || box ;" +
        "tv|Proyector| proyector , proyectores ;" +
        "tv|Handy| handy , handys , handies , walkie ;" +
        "tv|Consola y Mixer| consola , consolas , mixer , controlador , controladora , bandeja de dj , interface , interfaz , ecualizador ;" +
        "tv|Tocadiscos| tocadiscos , giradiscos , giradisco , bandeja , bendeja , bamdeja , tornamesa ;" +
        "tv|Radio| radio , radios , radiograbador , autoestereo , auto estereo ;" +
        "tv|Equipo de Música| minicomponente , microcomponente , sistema audio , sistema de audio , equipo de musica , home theater ;" +
        "tv|Parlante| parlante , parlantes , parlanteportatil , parl.port , parl.portatil , bafle , bafles , altavoz , altavoces , bocina , bocinas , speaker , speakers , subwoofer , torre , tower , sistema portatil , sistema portable , sistema de sonido , monitores , par de monitores , jbl , beats ";

    private record Regla(String cat, String sub, String[] lideres, String[] alguna, String[] veto) {}

    private static final List<Regla> REGLAS = Arrays.stream(VOCABULARIO.split(";", -1)).map(ElectroSubcategoryResolver::regla).toList();

    private static final Pattern PUNTUACION = Pattern.compile("[/:,;+()\\[\\]]");
    private static final Pattern BLANCOS = Pattern.compile("\\s+");
    // V128 and TextoClasificable.PREFIJO_DE_VENTA skip the same leading words, plus the pack words and brands that never name the product.
    private static final Pattern PREFIJO = Pattern.compile(
        "^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini|philips|ga[.]ma|gama|italy|professional"
        + "|x?[0-9]+(?:[.,][0-9]+)?)\\s+)+");

    boolean handles(String categoria) {
        return CLAVE.containsKey(categoria);
    }

    /** Call only when {@link #handles}. */
    String resolver(String categoria, String nombre) {
        String clave = CLAVE.get(categoria);
        String n = " " + BLANCOS.matcher(PUNTUACION.matcher(AccentStripper.strip(nombre.toLowerCase())).replaceAll(" ")).replaceAll(" ").trim() + " ";
        String sin = PREFIJO.matcher(n).replaceFirst(" ");
        for (Regla r : REGLAS) {
            if (r.cat().equals(clave) && empiezaCon(sin, r.lideres()) && (r.alguna() == null || contiene(n, r.alguna()))
                    && (r.veto() == null || !contiene(n, r.veto()))) {
                return r.sub();
            }
        }
        return "";
    }

    private static boolean empiezaCon(String texto, String[] palabras) {
        return Arrays.stream(palabras).anyMatch(texto::startsWith);
    }

    private static boolean contiene(String texto, String[] palabras) {
        return Arrays.stream(palabras).anyMatch(texto::contains);
    }

    private static Regla regla(String fila) {
        // split_part gives '' for a missing field, so a row may stop after its leaders
        String[] f = Arrays.copyOf(fila.split("\\|", -1), 5);
        return new Regla(f[0], f[1], f[2].split(",", -1), lista(f[3]), lista(f[4]));
    }

    private static String[] lista(String campo) {
        return StringUtils.isEmpty(campo) ? null : campo.split(",", -1);
    }
}
