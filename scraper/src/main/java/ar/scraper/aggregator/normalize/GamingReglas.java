package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Gaming rules read over the name's alphanumeric tokens ("Joy-Con (L/R)" is " joy con l r "), so a platform glued to punctuation
 * ("(Nintendo Switch)", "-PS4") still counts. {@link GamingClassifier} decides the category with them and
 * {@code SubcategoryResolver} the sub. V135 repeats every regex below as the same text over the same tokens
 * ({@code V135GamingAgreementTest} runs both over real names), so a change here needs the same change there.
 */
final class GamingReglas {

    private GamingReglas() {}

    /** Every platform or handheld a name can mention (the broad list: a case "for Switch" is an accessory). */
    static final String PLATAFORMA = " (?:ps1|ps2|ps3|ps4|ps5|psp|ps vita|psvita|ps vr|ps vr2|psvr|psvr2|playstation|xbox|nintendo|switch"
            + "|joy con|joycon|dualsense|dualshock|steam deck|rog ally|legion go|msi claw|3ds|2ds|dsi|nds|wii|game boy|gameboy"
            + "|gamecube|game cube|n64|snes|nes|famicom|sega|dreamcast|saturn|master system|atari|retro bit|retrobit|ns2|sw2) ";

    /**
     * What is sold for a console or a controller. The strong nouns are accessories whatever the title looks like; the weak ones
     * ("Spirit Camera - Nintendo 3DS", "Original Cover") also name games, so a title that ends in a platform tag is a game.
     */
    static final String ACCESORIO_FUERTE = "funda|fundas|estuche|estuches|case|cases|carrying|travel bag|travel case|hard shell|pouch"
            + "|protector|protectores|protection|grip|grips|dock|docking station|soporte|soportes|carcasa|skin|skins|adapter"
            + "|adaptador|converter|conversor|cargador|charger|cable|cables|battery|bateria|tempered glass|vidrio templado"
            + "|ventilador|enfriador|enfriamiento|refrigeracion|unidad lectora|unidad de disco|disc drive|armor|antideslizante"
            + "|sensor bar|barra sensora|gift card|membresia|membership|base de carga|reproductor remoto|playstation portal"
            + "|ps portal|joy con 2 wheel|joycon 2 wheel|leg strap|analogicos|boost pak|jumper pack|action replay|screen filter"
            + "|cover plates|hub|cubierta|cubiertas|accesorio|accesorios";
    static final String ACCESORIO_DEBIL = "stand|cover|covers|charging|charge|cooler|camera|camara|memory card|memoria|reader|lector"
            + "|nfc|eshop|tarjeta|estacion";
    static final String ACCESORIO = " (?:" + ACCESORIO_FUERTE + "|" + ACCESORIO_DEBIL + ") ";
    private static final String ACCESORIO_SOLO_FUERTE = " (?:" + ACCESORIO_FUERTE + ") ";

    /** A title that says it is a game whatever noun it carries ("Original Cover", a Limited Run edition). */
    static final String PISTA_DE_JUEGO = " (?:original cover|first print cover|cover reversible|reversible cover|limited run|pouch de regalo) ";

    /** Accessories that carry a controller word ("Joy-Con Charging Grip") and are still accessories. */
    static final String ACCESORIO_DE_CONTROL = " (?:charging grip|charge grip|comfort grip|charge stand|charge station|charging station"
            + "|charging case|usb adapter|wireless adapter|battery pack) ";

    static final String CONTROL_ALT = "controller|controllers|control|controles|controlador|controladores|mando|mandos|gamepad"
            + "|joypad|joy pad|joystick|joysticks|jostick|joy con|joycon|dualsense|dualshock|horipad|pro controller|arcade stick"
            + "|fight stick";
    static final String CONTROL = " (?:" + CONTROL_ALT + ") ";

    /** Not about a console although a platform word is there: a network or HDMI switch, a power strip, a keyboard, a notebook, merchandise. */
    static final String AJENO = " (?:kvm|gigabit|poe|ethernet|rj45|administrable|sfp|tp link|tplink|router|modem|cat5e|cat6|dimmer"
            + "|interruptor|domotica|hdmi switch|switch hdmi|usb hub|hub usb|keycap|keycaps|mecanico|teclado|keyboard|cuaderno|notebook"
            + "|notebooks|maceta|macetas|zapatilla|multiplicador|figure|figures|figura|figuras|collectible|collectibles|diorama"
            + "|playset|toy|slot car|cricut|cooler master) ";

    /** Over the name with its punctuation kept: a title that ends in " - Nintendo Switch" is a game even if it says "case". */
    static final String JUEGO_AL_FINAL = " - (?:(?:nintendo|sony|microsoft) )?(?:switch 2|switch|ps5|ps4|ps3|ps2|ps1|playstation [1-5]"
            + "|playstation vita|ps vita|psvita|3ds|2ds|ds|wii u|wii|xbox one|xbox 360|xbox series x|xbox series s|gamecube|n64)\\)? $";

    /** The platform words a game title uses (no bare "nintendo" or "sega": that is a brand, and sells merchandise). */
    static final String PLATAFORMA_DE_JUEGO = " (?:nintendo switch|switch 2|ps5|ps4|ps3|ps2|ps1|playstation [1-5]|playstation4|playstation5"
            + "|playstation vita|ps vita|psvita|psp|nintendo 3ds|3ds|2ds|nintendo ds|wii u|wii|xbox one|xbox 360|xbox series x"
            + "|xbox series s|gamecube|game boy advance|famicom|ns2) ";

    static final String CONTROL_DE_PLATAFORMA = " (?:controller|controllers|gamepad|joypad|joy pad|joy con|joycon|dualsense|dualshock"
            + "|horipad|pro controller|split pad|controles|mando|mandos|joystick|joysticks|jostick|joystck|controlador|controladores) ";

    /** Peripherals and merchandise that name a platform without being a game or a controller: the fallback rules skip them. */
    static final String PERIFERICO = " (?:headset|headsets|auricular|auriculares|headphone|headphones|volante|wheel|pedalera|pedal|pedales"
            + "|bolso|mochila|llavero|keychain|hanger|sticker|sleeve|paladone|mantel|vaso|taza|mug|fanatec|motor base|cockpit|sensor"
            + "|teclado|keyboard|mouse|monitor|hdmi|ssd|disco duro|hdd|pantalla|silla|capturadora|capture card|parlante|lampara|lamp"
            + "|microsd|sd card|power stick|memoria|bateria|battery) ";

    /** Racing gear sold for a console (a wheel base, a wheel with pedals). */
    static final String VOLANTE = " (?:motor base|volante|pedalera|racing wheel) ";

    /** A bundle of game and controller is sold as a game. */
    static final String PAQUETE = " bundle ";

    /** Not gaming whatever the platform says: clothing, drinkware and music gear that happen to carry a console name. */
    static final String FUERA_DE_GAMING = " (?:gorra|snapback|bottle|botella|pedalera de efectos|pedalera de multi efectos|footswitch"
            + "|escritorio|impresora 3d) ";

    /** A console named at the start of the name (a sale prefix is not skipped: V135 reads the same start). */
    static final String CONSOLA_AL_INICIO = "^ (?:consola|console) (?:de juegos |portable |portatil |digital |sony |nintendo )*"
            + "(?:retro|r36s|legion go|steam deck|rog ally|family|game stick|emuladora|emulador|playstation|ps5|ps4|xbox|switch"
            + "|nintendo 64|nintendo switch) "
            + "|^ (?:nintendo )?(?:new 3ds|new 2ds|wii mini|super nintendo classic|snes classic|nes classic|nintendo classic mini"
            + "|hyperkin retron|retron|supaboy|retro duo) (?!.*controllers? )"
            + "|^ nintendo game watch "
            + "|^ (?:paquete )?nintendo switch 2 (?:mario kart world bundle|bundle|elija su juego|console) ";

    private static final Pattern P_PLATAFORMA = Pattern.compile(PLATAFORMA);
    private static final Pattern P_ACCESORIO = Pattern.compile(ACCESORIO);
    private static final Pattern P_ACCESORIO_FUERTE = Pattern.compile(ACCESORIO_SOLO_FUERTE);
    private static final Pattern P_PISTA_DE_JUEGO = Pattern.compile(PISTA_DE_JUEGO);
    private static final Pattern P_ACCESORIO_DE_CONTROL = Pattern.compile(ACCESORIO_DE_CONTROL);
    private static final Pattern P_AJENO = Pattern.compile(AJENO);
    private static final Pattern P_JUEGO_AL_FINAL = Pattern.compile(JUEGO_AL_FINAL);
    private static final Pattern P_CONTROL_ANTES_DE_ACCESORIO =
            Pattern.compile("^(?:(?!" + ACCESORIO + ").)*(?!" + ACCESORIO + ") (?:" + CONTROL_ALT + ")(?!" + ACCESORIO + ") ");
    private static final Pattern P_FUERA_DE_GAMING = Pattern.compile(FUERA_DE_GAMING);
    private static final Pattern P_CONSOLA_AL_INICIO = Pattern.compile(CONSOLA_AL_INICIO);
    private static final Pattern P_PLATAFORMA_DE_JUEGO = Pattern.compile(PLATAFORMA_DE_JUEGO);
    private static final Pattern P_CONTROL_DE_PLATAFORMA = Pattern.compile(CONTROL_DE_PLATAFORMA);
    private static final Pattern P_PERIFERICO = Pattern.compile(PERIFERICO);
    private static final Pattern P_VOLANTE = Pattern.compile(VOLANTE);
    private static final Pattern P_PAQUETE = Pattern.compile(PAQUETE);
    private static final Pattern P_SWITCH_AL_FINAL = Pattern.compile(" switch $");

    /** " a b c " from a lower-cased, accent-stripped, space-padded name. */
    static String tokens(String normalizado) {
        StringBuilder sb = new StringBuilder(normalizado.length() + 2).append(' ');
        for (int i = 0; i < normalizado.length(); i++) {
            char c = normalizado.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
        }
        if (sb.length() == 1 || sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
        return sb.toString();
    }

    /** First word of every platform phrase: without one of them no platform rule can match, and the regexes are skipped. */
    private static final Set<String> PRIMERA_PALABRA_DE_PLATAFORMA = Stream.of(PLATAFORMA, PLATAFORMA_DE_JUEGO)
            .flatMap(regex -> Stream.of(regex.substring(" (?:".length(), regex.length() - ") ".length()).split("\\|")))
            .map(frase -> frase.split(" ")[0])
            .collect(Collectors.toUnmodifiableSet());

    static boolean puedeNombrarPlataforma(String tokens) {
        int desde = 1;
        for (int fin = tokens.indexOf(' ', desde); fin >= 0; fin = tokens.indexOf(' ', desde)) {
            if (PRIMERA_PALABRA_DE_PLATAFORMA.contains(tokens.substring(desde, fin))) return true;
            desde = fin + 1;
        }
        return false;
    }

    static boolean esFueraDeGaming(String tokens) {
        return P_FUERA_DE_GAMING.matcher(tokens).find();
    }

    static boolean esAccesorio(String normalizado, String tokens) {
        if (!P_PLATAFORMA.matcher(tokens).find() || !P_ACCESORIO.matcher(tokens).find()) return false;
        if (P_AJENO.matcher(tokens).find() || P_PISTA_DE_JUEGO.matcher(tokens).find()) return false;
        if (!P_ACCESORIO_FUERTE.matcher(tokens).find() && P_JUEGO_AL_FINAL.matcher(normalizado).find()) return false;
        return P_ACCESORIO_DE_CONTROL.matcher(tokens).find() || !P_CONTROL_ANTES_DE_ACCESORIO.matcher(tokens).find();
    }

    static boolean esConsola(String tokens) {
        return P_CONSOLA_AL_INICIO.matcher(tokens).lookingAt();
    }

    /** A controller that the older keyword rules did not place: a controller word and a platform, not sold as a bundle with a game. */
    static boolean esControl(String tokens) {
        return P_CONTROL_DE_PLATAFORMA.matcher(tokens).find() && P_PLATAFORMA.matcher(tokens).find()
                && !P_PAQUETE.matcher(tokens).find() && !P_AJENO.matcher(tokens).find() && !P_PERIFERICO.matcher(tokens).find();
    }

    /** Racing gear that names a platform. */
    static boolean esVolante(String tokens) {
        return P_VOLANTE.matcher(tokens).find() && P_PLATAFORMA.matcher(tokens).find() && !P_AJENO.matcher(tokens).find();
    }

    static boolean esVideojuego(String tokens) {
        return (P_PLATAFORMA_DE_JUEGO.matcher(tokens).find() || P_SWITCH_AL_FINAL.matcher(tokens).find())
                && !P_AJENO.matcher(tokens).find() && !P_PERIFERICO.matcher(tokens).find();
    }

    private record Regla(String sub, Pattern patron) {
        static Regla de(String sub, String regex) {
            return new Regla(sub, Pattern.compile(regex));
        }
    }

    /** First rule that matches wins; the order is the priority. V135 lists the same rows with the same regexes. */
    static final String[][] SUBS_CONSOLA = {
        {"pc portátil", " (?:steam deck|rog ally|legion go|msi claw|ayaneo|ayn odin|onexplayer|gpd win) "},
        // Sticks and plug-and-play boxes first: Powkiddy also sells a TV stick (Y6).
        {"emulador tv", " (?:game stick|gamestick|tv stick|emuladora|retroplay|retro play|k8|x8 pro) "},
        {"emulador portátil", "^(?!.* (?:switch|swithc|steam deck) )(?:.* (?:powkiddy|anbernic|miyoo|retroid|trimui|r36s|rebak|retroboy) "
                + "|(?=.* portatil ).* (?:retro|sup|[0-9]+ bits?|[0-9]+ juegos|[0-9]+ en 1) )"},
        {"emulador tv", " (?:family|nes av|mini drive|game drive|m8 hdmi|sega hdmi) "},
        {"retro", " (?:retro|anbernic|powkiddy|miyoo|retroid|game stick|gamestick|tv stick|nes mini|snes mini|nes classic|snes classic"
                + "|super nintendo classic|genesis mini|neo geo|classic mini|ps classic|playstation classic|flashback|emulador"
                + "|emuladora|fichines|joyplay|retroboy|r36s|my arcade|atari|sega|famicom|supaboy|retron|retro duo|family"
                + "|[0-9]+ en 1|[0-9]+ bits?) "},
        {"playstation", " (?:ps1|ps2|ps3|ps4|ps5|psp|ps vita|psvita|playstation|ps portal) "},
        {"xbox", " xbox "},
        {"nintendo switch", " (?:switch|swithc|nsw|ns2|sw2) "},
        {"nintendo", " (?:nintendo|3ds|2ds|dsi|nds|wii|game boy|gameboy|gba|gamecube|n64|snes|nes|virtual boy) "},
    };

    static final String[][] SUBS_VIDEOJUEGO = {
        {"switch 2", " (?:switch 2|switch2|ns2|sw2) "},
        {"switch", " (?:switch|nsw) "},
        {"ps5", " (?:ps5|playstation 5|playstation5) "},
        {"ps4", " (?:ps4|playstation 4|playstation4) "},
        {"playstation retro", " (?:ps3|ps2|ps1|ps vita|psvita|playstation vita|psp|playstation 3|playstation 2|playstation3|playstation2"
                + "|playstation portable) "},
        {"xbox", " xbox "},
        {"nintendo 3ds", " (?:3ds|2ds|nintendo ds|nds|dsi) "},
        {"wii", " (?:wii|wii u) "},
        {"pc", " (?:pc|steam|windows) "},
        {"retro", " (?:nes|snes|super nintendo|n64|nintendo 64|gamecube|game cube|game boy|gameboy|gba|sega|genesis|mega drive"
                + "|dreamcast|saturn|master system|atari|famicom|neo geo) "},
    };

    static final String[][] SUBS_JOYSTICK = {
        {"volante", " (?:volante|volantes|racing|driving|pedalera|pedales|pedal|shifter|palanca de cambios|handbrake|freno de mano"
                + "|simracing|sim racing|fanatec|moza) "},
        {"gamepad", " (?:gamepad|gamepads|joystick|joysticks|jostick|joystck|joypad|joy pad|joy con|joycon|controller|controllers"
                + "|control|controles|controlador|controladores|mando|mandos|dualsense|dualshock|pro controller|horipad|split pad"
                + "|nunchuk|pad) "},
    };

    private static final Map<String, List<Regla>> SUBS = Map.of(
            "Consola", reglas(SUBS_CONSOLA),
            "Videojuego", reglas(SUBS_VIDEOJUEGO),
            "Joystick", reglas(SUBS_JOYSTICK));

    private static List<Regla> reglas(String[][] filas) {
        return java.util.Arrays.stream(filas).map(f -> Regla.de(f[0], f[1])).toList();
    }

    static boolean handles(String categoria) {
        return SUBS.containsKey(categoria);
    }

    /** {@code ""} when no rule matches: an unnamed platform stays empty, never a guess. */
    static String sub(String categoria, String nombre) {
        String t = tokens(" " + AccentStripper.strip(nombre.toLowerCase()) + " ");
        for (Regla r : SUBS.get(categoria)) {
            if (r.patron().matcher(t).find()) return r.sub();
        }
        return "";
    }
}
