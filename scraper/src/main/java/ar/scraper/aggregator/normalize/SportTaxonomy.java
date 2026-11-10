package ar.scraper.aggregator.normalize;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sport code → keywords, in precedence order: when a name matches two sports the first entry wins.
 * Adding a sport is a new entry here plus a row in the {@code deporte} table (a migration).
 *
 * <p>Keywords are accent-stripped, lower case and matched as whole words by {@link SportResolver};
 * plurals are listed explicitly. Clubs go by full name ("river plate", never "river": Timberland
 * has a Kennebec River line) and "arena" is a colour, so it is not a swimming keyword.
 * V59 mirrors these lists in SQL.</p>
 */
public final class SportTaxonomy {

    private SportTaxonomy() {}

    public static final Map<String, String[]> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("futbol", new String[] {
            "futbol", "camiseta de futbol", "botin", "botines", "tachones", "arquero",
            "boca juniors", "river plate", "racing club", "independiente", "san lorenzo",
            "estudiantes", "velez", "newells", "rosario central", "talleres", "belgrano",
            "huracan", "lanus", "banfield", "gimnasia y esgrima", "argentinos juniors",
            "seleccion argentina", "afa",
            "real madrid", "fc barcelona", "barcelona fc", "barca", "manchester united",
            "manchester city", "juventus", "psg", "paris saint germain", "inter miami",
            "milan", "bayern", "liverpool", "chelsea", "arsenal"
        });
        // Ahead of natacion on purpose: Burton's snow goggles are "Antiparras Anon M5" and must not be swimming.
        // "anon" is Burton's goggle/helmet brand. No bare "fijaciones" (hardware bindings of any kind).
        KEYWORDS.put("snowboard", new String[] {
            "snowboard", "snowboards", "snowboarding", "snow", "ski", "skis", "esqui", "anon"
        });
        KEYWORDS.put("natacion", new String[] {
            "natacion", "malla de natacion", "antiparra", "antiparras", "gorra de natacion",
            "gorro de natacion", "swim", "swimwear", "aleta", "aletas", "speedo"
        });
        KEYWORDS.put("hockey", new String[] { "hockey" });
        KEYWORDS.put("rugby", new String[] { "rugby" });
        KEYWORDS.put("tenis", new String[] {
            "tenis", "padel", "paddle", "raqueta", "raquetas"
        });
        KEYWORDS.put("basquet", new String[] {
            "basquet", "basquetbol", "basket", "basketball", "nba"
        });
        KEYWORDS.put("voley", new String[] { "voley", "voleibol", "volley", "volleyball" });
        KEYWORDS.put("ciclismo", new String[] {
            "ciclismo", "ciclista", "ciclistas", "maillot", "mtb"
        });
        KEYWORDS.put("golf", new String[] { "golf" });
        // No bare "box" (boxer underwear, a box) and no "protector bucal" (shared with rugby/hockey).
        KEYWORDS.put("combate", new String[] {
            "boxeo", "guantes de box", "guantes de boxeo", "vendas de boxeo", "bolsa de boxeo",
            "kickboxing", "kick boxing", "mma", "muay thai", "judo", "karate", "taekwondo",
            "jiu jitsu", "bjj"
        });
    }
}
