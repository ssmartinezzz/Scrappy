package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            new String[]{"deportiva",  "deportiva", "gym", "training", "entrenamiento", "fitness", "sport"}
        ));
        SUBCATEG_TIER1.put("Calzoncillos", Arrays.<String[]>asList(
            new String[]{"boxer",  "boxer"},
            new String[]{"slip",   "slip"},
            new String[]{"brief",  "brief"}
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
            new String[]{"deportiva",   "deportiva", "gym", "training", "entrenamiento",
                                        "compression", "compresion", "dri-fit"},
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
        SUBCATEG_TIER1.put("Vestido", Arrays.<String[]>asList(
            new String[]{"largo",  "largo", "maxi"},
            new String[]{"corto",  "corto", "mini"},
            new String[]{"fiesta", "fiesta", "noche"}
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

    /**
     * {@code ""} — never {@code null}. All comparison is accent/case-insensitive (via
     * {@link #normalizarAcentos}).
     */
    public String resolver(String nombre, String categoria) {
        if (StringUtils.isBlank(nombre) || categoria == null) return "";
        // Space-padded, accent-normalized name — enables safe word-boundary matching
        String n = " " + normalizarAcentos(nombre) + " ";

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

    private String normalizarAcentos(String s) {
        return AccentStripper.strip(s.toLowerCase());
    }
}
