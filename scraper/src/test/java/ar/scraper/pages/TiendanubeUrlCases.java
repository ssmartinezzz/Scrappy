package ar.scraper.pages;

import java.util.LinkedHashMap;
import java.util.Map;

/** Input to expected output of the Tiendanube canonical URL, shared by the Java and the SQL checks. */
public final class TiendanubeUrlCases {

    public static final Map<String, String> CASOS = new LinkedHashMap<>();

    static {
        String p = "https://tienda.test/productos/remera/";
        CASOS.put(p + "?entry_path=category", p);
        CASOS.put(p + "?entry_path=category&entry_category_id=41773608", p);
        CASOS.put(p + "?entry_category_id=7&entry_path=category", p);
        CASOS.put(p + "?entry_path=category&variant=99", p + "?variant=99");
        CASOS.put(p + "?variant=99&entry_path=category", p + "?variant=99");
        CASOS.put(p + "?a=1&entry_path=category&b=2&entry_category_id=5&c=3", p + "?a=1&b=2&c=3");
        CASOS.put(p + "?entry_path=category#resenas", p + "#resenas");
        CASOS.put(p + "?variant=99&entry_path=category#resenas", p + "?variant=99#resenas");
        CASOS.put(p + "?entry_path=", p);
        CASOS.put(p + "?entry_path", p);
        CASOS.put(p + "?entry_path=category&", p);
        CASOS.put(p + "?entry_path=category&&x=1", p + "?&x=1");
        CASOS.put(p + "?x=a=b&entry_path=category", p + "?x=a=b");
        CASOS.put(p + "?my_entry_path=1&entry_path=category", p + "?my_entry_path=1");
        CASOS.put(p + "?entry_path_x=1&entry_path=category", p + "?entry_path_x=1");
        CASOS.put(p + "?x=entry_path=1&entry_path=category", p + "?x=entry_path=1");
        CASOS.put(p + "#a?entry_path=category", p + "#a?entry_path=category");
        CASOS.put(p + "?x=1#a?entry_path=category", p + "?x=1#a?entry_path=category");
        CASOS.put(p + "?entry_path=category#a?x=1&entry_category_id=2", p + "#a?x=1&entry_category_id=2");
        CASOS.put(p + "?ENTRY_PATH=category", p + "?ENTRY_PATH=category");
        CASOS.put(p + "?x=1", p + "?x=1");
        CASOS.put(p, p);
    }

    private TiendanubeUrlCases() {
    }
}
