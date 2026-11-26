package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What belongs to {@code Networking} beyond the {@code KW_RED} keywords, and its sub-category. Both read the name's alphanumeric
 * tokens ("Cat.5e" is " cat 5e "). V141 repeats every regex below as the same text over the same tokens
 * ({@code V141NetworkingAgreementTest} runs both over real names), so a change here needs the same change there.
 */
final class NetworkingReglas {

    private NetworkingReglas() {}

    /** The sale words a leading noun skips: "Pack 3 Cables ...", "OUTLET - Adaptador ...". */
    private static final String PREFIJO = "(?:(?:pack|kit|combo|set|x|de|outlet|[0-9]+) )*";

    /** A mesh system or a range extender the {@code KW_RED} keywords do not name ("Mesh Tp-Link Deco E4", "Extensor WiFi RE200"). */
    static final String NOMBRA_RED = "^(?=.* mesh )(?=.* (?:wi ?fi|deco|router|tp link|tplink|access point) )"
            + "| (?:extensor|extensores) (?:de )?(?:wi ?fi|red|senal|rango) ";

    /** What makes a cable, a plug or an adapter a network one. */
    static final String SENAL_DE_CABLE = " (?:cable de red|cables de red|patch cord|patchcord|patchcords|patch cable|utp|ftp|sftp|sstp"
            + "|rj ?45|cat ?[5-8][ae]?|categoria [5-8][ae]?|ethernet|lan|keystone) ";

    /** HDMI, telephone and coaxial cables name Ethernet or a jack without being network cables. */
    static final String NO_ES_DE_RED = " (?:hdmi|displayport|display port|vga|dvi|rj ?11|rj ?9|coaxial|audio|rca) ";

    /** A multiport hub or dock that lists an RJ45 among its outputs stays a Cable. */
    static final String CONCENTRADOR = " (?:[0-9]+ en 1|[0-9]+ in 1|multipuerto|multiport|dock|docking) |^ (?:adaptador )?hub ";

    private static final String CONVERSION = " (?:usb|type c|thunderbolt)(?: [a-z0-9]+){0,4} (?:a|to) (?:gigabit |giga )?(?:ethernet|rj ?45|lan|red) ";

    private static final Pattern P_NOMBRA_RED = Pattern.compile(NOMBRA_RED);
    private static final Pattern P_SENAL_DE_CABLE = Pattern.compile(SENAL_DE_CABLE);
    private static final Pattern P_NO_ES_DE_RED = Pattern.compile(NO_ES_DE_RED);
    private static final Pattern P_CONCENTRADOR = Pattern.compile(CONCENTRADOR);

    /** First rule that matches wins; the order is the priority. */
    static final String[][] SUBS = {
        {"Conector", "^ " + PREFIJO + "(?:ficha|fichas|conector|conectores|plug|plugs|jack|jacks|keystone|modulo keystone|acoplador|acopladores) "},
        {"Cable de Red", "^(?!.*" + CONVERSION + ") " + PREFIJO + "(?:cable|cables|patch cord|patchcord|patchcords|utp) "},
        {"Placa y Adaptador", "^ " + PREFIJO + "(?:placa|placas|adaptador|adaptadores|tarjeta de red) "},
        {"Mesh", " (?:deco|velop|eero|orbi) |^(?:(?! (?:one|easy) mesh ).)* mesh "},
        {"Access Point", " (?:access point|accesspoint|ap[0-9]*|cpe[0-9]*|eap[0-9]+[a-z]*|nanostation|nanobeam|powerbeam|power beam) "},
        {"Repetidor", " (?:repetidor|repetidores|extensor|extensores|range extender|wifi extender|wi fi extender) "},
        {"Router", " (?:router|routers|modem|modems|xpon|gpon) "},
        {"Switch", "^(?!.* hub ).* (?:switch|switches|fortiswitch) "},
        {"Placa y Adaptador", " (?:placa|placas|adaptador|adaptadores|adapter|dongle|receptor|nic|tarjeta de red) "},
    };

    private record Regla(String sub, Pattern patron) {}

    private static final List<Regla> REGLAS = java.util.Arrays.stream(SUBS).map(f -> new Regla(f[0], Pattern.compile(f[1]))).toList();

    static boolean handles(String categoria) {
        return "Networking".equals(categoria);
    }

    /** {@code ""} when no rule matches: a name that says nothing about what it is stays empty, never a guess. */
    static String sub(String nombre) {
        String t = GamingReglas.tokens(" " + AccentStripper.strip(nombre.toLowerCase()) + " ");
        for (Regla r : REGLAS) {
            if (r.patron().matcher(t).find()) return r.sub();
        }
        return "";
    }

    static boolean nombraRed(TextoClasificable texto) {
        if (!texto.contiene("mesh") && !texto.contiene("extensor")) return false;
        return P_NOMBRA_RED.matcher(GamingReglas.tokens(texto.normalizado())).find();
    }

    /** A network cable, plug or adapter. The caller has checked that the name starts with a cable or plug noun. */
    static boolean esCableOConector(TextoClasificable texto) {
        String t = GamingReglas.tokens(texto.normalizado());
        return P_SENAL_DE_CABLE.matcher(t).find() && !P_NO_ES_DE_RED.matcher(t).find() && !P_CONCENTRADOR.matcher(t).find();
    }
}
