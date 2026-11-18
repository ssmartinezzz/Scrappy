package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.SocketDeNombre;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * The local accent-normalization helper now delegates to {@link AccentStripper#strip} (ADR-4)
 * instead of duplicating the 6-replacement regex chain.
 */
@Component
public class SubcategoryResolver {

    /**
     * {@code entry[1..n]} = keywords to match (accent/case-insensitive, space-padded for
     * word-boundary safety). An entry with only one element (no keywords) acts as an unconditional
     * default for that category.
     */
    private static final Map<String, List<String[]>> SUBCATEG_TIER1;
    static {
        SUBCATEG_TIER1 = new LinkedHashMap<>();
        SUBCATEG_TIER1.put("Gorro", Arrays.<String[]>asList(
            new String[]{"natación",   "natacion", "swimming", "swim cap", "pileta"},
            new String[]{"ski",        "ski", "snowboard", "snowboarding", "nieve"},
            new String[]{"invierno"}
        ));
        SUBCATEG_TIER1.put("Malla", Arrays.<String[]>asList(
            new String[]{"bikini",     "bikini", "triangulo", "triangular"},
            new String[]{"entera",     "one piece", "entera", "enteriza"},
            new String[]{"tankini",    "tankini"}
        ));
        SUBCATEG_TIER1.put("Short", Arrays.<String[]>asList(
            new String[]{"natación",   "natacion", "bano", "swim"},
            new String[]{"cargo",      "cargo"},
            new String[]{"gym",        "gym", "training", "entrenamiento", "fitness"}
        ));
        SUBCATEG_TIER1.put("Campera", Arrays.<String[]>asList(
            new String[]{"outdoor",      "hiking", "trekking", "montana", "outdoor", "trail"},
            new String[]{"running",      "running", "runner"},
            new String[]{"polar",        "polar", "fleece"},
            new String[]{"rompevientos", "rompevientos", "cortavientos", "cortaviento", "wind"}
        ));
        SUBCATEG_TIER1.put("Conjunto", Arrays.<String[]>asList(
            new String[]{"deportivo",  "deportivo", "gym", "training", "entrenamiento",
                                       "running", "fitness", "sport"},
            new String[]{"interior",   "interior", "intimo", "lenceria"},
            new String[]{"baño",       "bano", "swim", "natacion"}
        ));
        SUBCATEG_TIER1.put("Calza", Arrays.<String[]>asList(
            new String[]{"running",    "running", "runner"},
            new String[]{"ciclista",   "ciclista", "cycling", "ciclismo"},
            new String[]{"térmica",    "termica"}
        ));
        SUBCATEG_TIER1.put("Musculosa", Arrays.<String[]>asList(
            new String[]{"compresión", "compression", "compresion"},
            new String[]{"deportiva",  "deportiva", "gym", "training", "entrenamiento", "fitness", "sport"}
        ));
        SUBCATEG_TIER1.put("Calzoncillos", Arrays.<String[]>asList(
            new String[]{"boxer",  "boxer"},
            new String[]{"slip",   "slip"},
            new String[]{"brief",  "brief"}
        ));
        SUBCATEG_TIER1.put("Bombacha", Arrays.<String[]>asList(
            new String[]{"tanga",     "tanga"},
            new String[]{"colaless",  "colaless", "cola less"},
            new String[]{"vedetina",  "vedetina"},
            new String[]{"culotte",   "culotte"}
        ));
        SUBCATEG_TIER1.put("Corpino", Arrays.<String[]>asList(
            new String[]{"deportivo",   "deportivo", "training", "gym", "sport", "fitness"},
            new String[]{"push-up",     "push up", "pushup", "push-up"},
            new String[]{"triangular",  "triangular", "triangulo"},
            new String[]{"bralette",    "bralette"}
        ));
        SUBCATEG_TIER1.put("Medias", Arrays.<String[]>asList(
            new String[]{"deportivas",  "deportivas", "sport", "running"},
            new String[]{"compresión",  "compresion"},
            new String[]{"tobilleras",  "tobilleras", "tobillo"}
        ));
        SUBCATEG_TIER1.put("Buzo", Arrays.<String[]>asList(
            new String[]{"hoodie",  "hoodie", "capucha", "canguro"}
        ));
        SUBCATEG_TIER1.put("Bolso", Arrays.<String[]>asList(
            new String[]{"tote",      "tote"},
            new String[]{"cartera",   "mano", "cartera"},
            new String[]{"mensajero", "mensajero", "crossbody"}
        ));
        SUBCATEG_TIER1.put("Mochila", Arrays.<String[]>asList(
            new String[]{"trekking",  "trekking", "hiking", "outdoor"},
            new String[]{"deportiva", "deportiva", "gym", "sport"},
            new String[]{"notebook",  "notebook", "laptop", "portanotebook"}
        ));

        SUBCATEG_TIER1.put("Almacenamiento", Arrays.<String[]>asList(
            new String[]{"nvme",      "nvme", "m.2"},
            new String[]{"ssd",       "ssd"},
            new String[]{"hdd",       "hdd", "rigido", "duro"},
            new String[]{"pendrive",  "pendrive"},
            new String[]{"microsd",   "microsd", "micro sd", "sd"}
        ));
        SUBCATEG_TIER1.put("Red", Arrays.<String[]>asList(
            new String[]{"router",     "router"},
            new String[]{"switch",     "switch"},
            new String[]{"repetidor",  "repetidor", "extensor"},
            new String[]{"adaptador",  "adaptador", "placa", "antena"}
        ));
        SUBCATEG_TIER1.put("Cable", Arrays.<String[]>asList(
            new String[]{"video",   "hdmi", "displayport", "display port", "vga", "dvi"},
            new String[]{"red",     "rj45", "rj-45", "patchcord", "ethernet"},
            new String[]{"usb",     "usb"},
            new String[]{"poder",   "power", "alimentacion", "poder"}
        ));
        SUBCATEG_TIER1.put("Impresión", Arrays.<String[]>asList(
            new String[]{"impresora", "impresora", "multifuncion"},
            new String[]{"toner",     "toner"},
            new String[]{"tinta",     "tinta", "cartucho"}
        ));
        SUBCATEG_TIER1.put("Cooler", Arrays.<String[]>asList(
            new String[]{"líquida",  "water", "aio", "liquida", "watercooling"},
            new String[]{"gabinete", "gabinete", "case"},
            new String[]{"pasta",    "grasa", "pasta"},
            new String[]{"aire",     "aire", "torre", "disipador"}
        ));
        SUBCATEG_TIER1.put("Fuente", Arrays.<String[]>asList(
            new String[]{"modular",  "modular"},
            new String[]{"80 plus",  "plus", "bronze", "gold", "platinum"}
        ));
        SUBCATEG_TIER1.put("Teclado", Arrays.<String[]>asList(
            new String[]{"mecánico",     "mecanico", "mechanical"},
            new String[]{"inalámbrico",  "inalambrico", "wireless", "bluetooth"},
            new String[]{"gamer",        "gamer", "gaming"}
        ));
        SUBCATEG_TIER1.put("Mouse", Arrays.<String[]>asList(
            new String[]{"inalámbrico", "inalambrico", "wireless", "bluetooth"},
            new String[]{"gamer",       "gamer", "gaming"}
        ));
        SUBCATEG_TIER1.put("Monitor", Arrays.<String[]>asList(
            new String[]{"curvo",   "curvo", "curved"},
            new String[]{"gaming",  "gaming", "gamer", "144hz", "165hz", "240hz"},
            new String[]{"4k",      "4k", "uhd", "qhd"}
        ));
        SUBCATEG_TIER1.put("Notebook", Arrays.<String[]>asList(
            new String[]{"gamer",   "gamer", "gaming"},
            new String[]{"oficina", "oficina", "business"}
        ));
        SUBCATEG_TIER1.put("Reloj", Arrays.<String[]>asList(
            new String[]{"smartwatch", "smartwatch", "smart", "inteligente"},
            new String[]{"deportivo",  "deportivo", "running", "gps"}
        ));
        SUBCATEG_TIER1.put("Joystick", Arrays.<String[]>asList(
            new String[]{"volante",   "volante", "racing", "driving"},
            new String[]{"gamepad",   "gamepad", "joystick", "control"}
        ));
        SUBCATEG_TIER1.put("Cámara", Arrays.<String[]>asList(
            new String[]{"exterior",  "exterior"},
            new String[]{"interior",  "interior"},
            new String[]{"wifi",      "wifi", "ip"}
        ));
        SUBCATEG_TIER1.put("Paleta", Arrays.<String[]>asList(
            new String[]{"ping pong", "ping", "pong", "tenis de mesa"}
        ));

        SUBCATEG_TIER1.put("Remera", Arrays.<String[]>asList(
            new String[]{"compresión",  "compression", "compresion"},
            new String[]{"deportiva",   "deportiva", "gym", "training", "entrenamiento", "dri-fit"},
            new String[]{"manga larga", "larga"},
            new String[]{"oversize",    "oversize", "oversized", "boxy", "remeron"},
            new String[]{"estampada",   "estampada", "print", "estampado"}
        ));
        SUBCATEG_TIER1.put("Camisa", Arrays.<String[]>asList(
            new String[]{"lino",     "lino"},
            new String[]{"cuadros",  "cuadros", "flannel", "lumberjack", "checked"},
            new String[]{"oxford",   "oxford", "chambray"},
            new String[]{"denim",    "denim", "jean"}
        ));
        SUBCATEG_TIER1.put("Jean", Arrays.<String[]>asList(
            new String[]{"cargo",   "cargo"},
            new String[]{"mom",     "mom"},
            new String[]{"skinny",  "skinny", "chupin"},
            new String[]{"recto",   "recto", "straight"}
        ));
        SUBCATEG_TIER1.put("Pantalón", Arrays.<String[]>asList(
            new String[]{"cargo",   "cargo"},
            new String[]{"vestir",  "vestir", "sastrero", "sastre"},
            new String[]{"jogger",  "jogger", "joggers"},
            new String[]{"lino",    "lino"}
        ));
        SUBCATEG_TIER1.put("Gorra", Arrays.<String[]>asList(
            new String[]{"trucker",  "trucker"},
            new String[]{"snapback", "snapback"},
            new String[]{"visera",   "visera"}
        ));
        // The franchise of a trading card game; the first match wins. Sub-categories are lowercase, like the chips.
        SUBCATEG_TIER1.put("TCG", Arrays.<String[]>asList(
            new String[]{"pokemon",     "pokemon"},
            new String[]{"dragon ball", "dragon ball"},
            new String[]{"magic",       "magic the gathering", "magic:", "mtg"},
            new String[]{"yu-gi-oh",    "yu-gi-oh", "yu-gi-oh!", "yugioh", "yu gi oh"},
            new String[]{"one piece",   "one piece"},
            new String[]{"lorcana",     "lorcana"},
            new String[]{"digimon",     "digimon"}
        ));
        SUBCATEG_TIER1.put("Vestido", Arrays.<String[]>asList(
            new String[]{"largo",  "largo", "maxi"},
            new String[]{"corto",  "corto", "mini"},
            new String[]{"fiesta", "fiesta", "noche"}
        ));
        SUBCATEG_TIER1.put("Libro", Arrays.<String[]>asList(
            new String[]{"Manga", "manga", "mangas", "ivrea", "kanzenban", "databook", "tomo", "ovni", "kemuri",
                                  "dandadan", "one piece", "blue lock", "captain tsubasa", "cardcaptor sakura",
                                  "frieren", "fullmetal alchemist", "demon slayer", "yu yu hakusho", "inio asano",
                                  "the legend of zelda 03:"}
        ));
        // First match wins, so a name that mentions both an eau de parfum and an eau de toilette is a parfum.
        SUBCATEG_TIER1.put("Perfume", Arrays.<String[]>asList(
            new String[]{"Eau de Parfum",    "eau de parfum", "edp", "parfum"},
            new String[]{"Eau de Toilette",  "eau de toilette", "edt"},
            new String[]{"Body Splash",      "body splash", "body mist", "splash"},
            new String[]{"Colonia",          "colonia", "eau de cologne"}
        ));
        SUBCATEG_TIER1.put("Botines", Arrays.<String[]>asList(
            new String[]{"futsal",  "futsal", "papi", "salon", "ic"},
            new String[]{"campo",   "campo", "fg", "cesped"}
        ));
    }

    /** Evaluated in order after tier-1 produces no match. */
    private static final List<String[]> SUBCATEG_TIER2 = Arrays.<String[]>asList(
        new String[]{"natación",   "natacion", "swimming", "swim cap"},
        new String[]{"hockey",     "hockey"},
        new String[]{"fútbol",     "futbol", "football"},
        new String[]{"pádel",      "padel"},
        new String[]{"tenis",      "tenis", "tennis"},
        new String[]{"running",    "running", "runner"},    // guard: skipped if categoria contains "running"
        new String[]{"vóley",      "voley", "volleyball"},
        new String[]{"básquet",    "basket", "basketball"},
        new String[]{"ciclismo",      "ciclismo", "cycling"},
        new String[]{"escalada",      "escalada", "climbing"},
        new String[]{"snowboarding",  "snowboard", "snowboarding"}
    );

    /** Hardware whose sub is its platform, read from alphanumeric tokens (see {@link #hardware}). */
    private static final Set<String> HARDWARE = Set.of("CPU", "Motherboard", "RAM", "GPU", "Auricular", "PC");

    /** Platforms with a sub; any other socket (AM3, LGA2011...) is outside the taxonomy and stays empty. */
    private static final Set<String> SOCKETS_CON_SUB = Set.of("am4", "am5", "lga1700", "lga1851", "lga1200", "lga1151");

    /**
     * Technology categories never get a sport sub: tier 2 is for garments, and "Mario Tennis" is not a tennis product.
     * V103 clears stale sport subs on exactly this set. Reloj is out on purpose: a running watch is a sport product.
     */
    static final Set<String> TECNOLOGIA_SIN_DEPORTE;
    static {
        TECNOLOGIA_SIN_DEPORTE = Set.of("CPU", "Motherboard", "RAM", "GPU", "Auricular", "PC", "Gabinete",
            "Almacenamiento", "Red", "Cable", "Impresión", "Cooler", "Fuente", "Teclado", "Mouse", "Monitor",
            "Notebook", "Joystick", "Cámara", "Videojuego", "Mousepad", "Combo de Actualización", "Silla",
            "TV y Audio", "Micrófono", "Tablet", "Webcam", "Consola", "Mini PC", "UPS", "Soporte Monitor",
            "Escritorio");
    }

    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    private static final Pattern RX_MODELO = Pattern.compile("rx\\d{3,4}\\w*");
    private static final Pattern GT_MODELO = Pattern.compile("gt\\d{3,4}\\w*");
    private static final Pattern DIGITOS_3_4 = Pattern.compile("\\d{3,4}\\w*");
    private static final Pattern ARC_MODELO = Pattern.compile("[ab]\\d{2,3}\\w*");
    private static final Pattern RADEON_R = Pattern.compile("r[579]");
    private static final Pattern GT_AL_REVES = Pattern.compile("\\d{3,4}gt");

    // V103 uses the same regex text on the same padded tokens; keep the two in step (SubcategoryResolverTecnologiaTest).
    private static final Pattern VELOCIDAD_RAM = Pattern.compile("([0-9]{3,5}) ?(?:mhz|mts|mt s|mt) ");
    private static final Pattern AURICULAR_GAMER = Pattern.compile(
        " (?:gamers?|gaming|quantum|hyperx|virtuoso|redragon|astro|razer|arctis|fantech|cougar|aula|gxt|rog|tuf"
        + "|afterglow|pdp|vsg|raptor|surround|rgb|gx|7 1|g pro|hs g[0-9]+[a-z]*) ");
    private static final Pattern HS_GENIUS = Pattern.compile(" hs [0-9]+[a-z]* ");
    private static final Pattern AURICULAR_EN_OREJA = Pattern.compile(
        " (?:earbuds?|buds|tws|true wireless) ");
    private static final Pattern HS_CORSAIR = Pattern.compile(" hs[0-9]+[a-z]* ");
    private static final Pattern G_LOGITECH = Pattern.compile(" g[0-9]{3}[a-z]* ");
    private static final Pattern H_LOGITECH = Pattern.compile(" h[0-9]{3}[a-z]* ");
    private static final Pattern CH_COOLER_MASTER = Pattern.compile(" ch[0-9]+[a-z]* ");

    /**
     * {@code ""} — never {@code null}. All comparison is accent/case-insensitive (via
     * {@link #normalizarAcentos}).
     */
    public String resolver(String nombre, String categoria) {
        if (StringUtils.isBlank(nombre) || categoria == null) return "";
        // Space-padded, accent-normalized name — enables safe word-boundary matching
        String n = " " + normalizarAcentos(nombre) + " ";

        if (HARDWARE.contains(categoria)) return hardware(categoria, nombre);

        List<String[]> tier1 = SUBCATEG_TIER1.get(categoria);
        if (tier1 != null) {
            for (String[] entry : tier1) {
                if (entry.length == 1) {
                    return entry[0];
                }
                for (int i = 1; i < entry.length; i++) {
                    if (n.contains(" " + entry[i] + " ")) {
                        return entry[0];
                    }
                }
            }
        }

        // Same for a Funko: "Funko Pop! Alien 3 - Ripley & The Runner" is not a running product.
        if ("Juguete".equals(categoria) && n.contains(" funko")) return "";

        // The sport tier is for garments; "Perfume Hockey Club" is not a hockey product, nor is a card or a LEGO set.
        if ("Perfume".equals(categoria) || "TCG".equals(categoria) || "LEGO".equals(categoria)) return "";

        if (TECNOLOGIA_SIN_DEPORTE.contains(categoria)) return "";

        String catNorm = normalizarAcentos(categoria);
        for (String[] entry : SUBCATEG_TIER2) {
            String subcat = entry[0];
            // Running guard: skip when categoria already contains "running"
            if ("running".equals(subcat) && catNorm.contains("running")) continue;
            for (int i = 1; i < entry.length; i++) {
                if (n.contains(" " + entry[i] + " ")) {
                    return subcat;
                }
            }
        }

        return "";
    }

    /**
     * Tokens, not the padded name: the platform words arrive glued to punctuation ("AM5,", "(am5)", "S.1700"). V103
     * normalizes the same way. {@code ""} when the name does not say, never a guess.
     */
    private String hardware(String categoria, String nombre) {
        String[] t = NO_ALFANUMERICO.split(normalizarAcentos(nombre).trim());
        String padded = " " + String.join(" ", t) + " ";
        return switch (categoria) {
            case "CPU" -> socketConSub(SocketDeNombre.cpu(t, padded));
            case "Motherboard" -> socketConSub(SocketDeNombre.motherboard(t, SocketDeNombre.chipsetToken(t)));
            case "RAM" -> ram(t, padded);
            case "GPU" -> gpu(t);
            case "Auricular" -> auricular(t, padded);
            default -> fabricanteGpu(t, false).isEmpty() ? "oficina" : "gamer"; // PC: a dedicated GPU makes it gamer
        };
    }

    private static String socketConSub(String socket) {
        String sub = socket.toLowerCase();
        return SOCKETS_CON_SUB.contains(sub) ? sub : "";
    }

    private static String ram(String[] t, String padded) {
        if (SocketDeNombre.tiene(t, "sodimm") || padded.contains(" so dimm ")
                || SocketDeNombre.tiene(t, "notebook") || SocketDeNombre.tiene(t, "laptop")) return "notebook";
        if (SocketDeNombre.tiene(t, "ddr5") || SocketDeNombre.tiene(t, "dddr5")) return "ddr5";
        if (SocketDeNombre.tiene(t, "ddr4") || SocketDeNombre.tiene(t, "dddr4")) return "ddr4";
        if (SocketDeNombre.tiene(t, "ddr3")) return "ddr3";
        if (SocketDeNombre.tiene(t, "ddr2") || SocketDeNombre.tiene(t, "ddr")) return ""; // an old or unnamed generation: no speed guess
        return ddrPorVelocidad(padded);
    }

    /** No DDR token: the speed names the generation. DDR3 stops at 1866, DDR4 spans 2133-3600, DDR5 starts at 4800. */
    private static String ddrPorVelocidad(String padded) {
        Matcher m = VELOCIDAD_RAM.matcher(padded);
        if (!m.find()) return "";
        int mhz = Integer.parseInt(m.group(1));
        if (mhz >= 4800) return "ddr5";
        if (mhz >= 2133 && mhz <= 3600) return "ddr4";
        if (mhz <= 1866) return "ddr3";
        return "";
    }

    private static String gpu(String[] t) {
        return fabricanteGpu(t, true);
    }

    /**
     * Chip maker. A bare "radeon" is an integrated GPU in a CPU or PC name ("Radeon Vega 11"), so it only counts
     * when {@code radeonSolo} (the GPU category itself); "RX 9060" always does. The same goes for "Nvidia 1030GT"
     * and "Radeon R5 230" written without a series word: a PC name can say "Nvidia" or "R5 5600G" for other reasons. A lone "rx" or "arc" is a cooler or a manga.
     */
    private static String fabricanteGpu(String[] t, boolean radeonSolo) {
        boolean nvidia = false;
        boolean amd = false;
        boolean intel = false;
        for (int i = 0; i < t.length; i++) {
            String s = t[i];
            String sig = i + 1 < t.length ? t[i + 1] : "";
            if ((radeonSolo && (s.equals("nvidia") || GT_AL_REVES.matcher(s).matches()))
                    || s.equals("geforce") || s.equals("quadro") || s.startsWith("rtx") || s.startsWith("gtx")
                    || GT_MODELO.matcher(s).matches()
                    || (s.equals("gt") && DIGITOS_3_4.matcher(sig).matches())) nvidia = true;
            if ((radeonSolo && (s.equals("radeon") || (RADEON_R.matcher(s).matches() && DIGITOS_3_4.matcher(sig).matches())))
                    || RX_MODELO.matcher(s).matches()
                    || (s.equals("rx") && DIGITOS_3_4.matcher(sig).matches())) amd = true;
            String modelo = sig.equals("pro") && i + 2 < t.length ? t[i + 2] : sig; // "Arc Pro B60"
            if (s.equals("arc") && ARC_MODELO.matcher(modelo).matches()) intel = true;
        }
        return nvidia ? "nvidia" : amd ? "amd" : intel ? "intel" : "";
    }

    /**
     * In-ear first when the name says buds / TWS / true wireless (they are wireless, but in-ear is what they are),
     * then wireless > gamer > office > the generic in-ear words. Typos seen in the data count: wireles, bluetooh.
     */
    private static String auricular(String[] t, String padded) {
        if (AURICULAR_EN_OREJA.matcher(padded).find()) return "in-ear";
        for (String s : t) {
            if (s.startsWith("inalambric") || s.equals("wireless") || s.equals("wireles")
                    || s.equals("bluetooth") || s.equals("bluetooh")) return "inalámbrico";
        }
        if (esAuricularGamer(padded)) return "gamer";
        if (esAuricularOficina(padded)) return "oficina";
        if (padded.contains(" in ear ") || SocketDeNombre.tiene(t, "earphone") || SocketDeNombre.tiene(t, "earphones")
                || SocketDeNombre.tiene(t, "intraural")) return "in-ear";
        return "";
    }

    /** Call-centre lines: Logitech H###/Zone, Genius HS-### (the HS-G### ones are gaming and matched before). */
    private static boolean esAuricularOficina(String padded) {
        return padded.contains(" zone ")
                || padded.contains(" logitech ") && H_LOGITECH.matcher(padded).find()
                || padded.contains(" genius ") && HS_GENIUS.matcher(padded).find();
    }

    private static boolean esAuricularGamer(String padded) {
        return AURICULAR_GAMER.matcher(padded).find()
                || padded.contains(" corsair ") && HS_CORSAIR.matcher(padded).find()
                || padded.contains(" logitech ") && G_LOGITECH.matcher(padded).find()
                || padded.contains(" cooler master ") && CH_COOLER_MASTER.matcher(padded).find();
    }

    private String normalizarAcentos(String s) {
        return AccentStripper.strip(s.toLowerCase());
    }
}
