package ar.scraper.aggregator.grouping;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brands recognised inside a product name, for rows whose {@code marca} is blank. Chip makers
 * (nvidia, amd, intel, geforce, radeon) are deliberately absent: they are not the seller's brand.
 */
final class NameBrands {

    private static final Map<String, String> ALIAS = Map.of(
            "xpg", "adata", "aorus", "gigabyte", "western digital", "wd",
            "team group", "teamgroup", "tp-link", "tplink", "tp link", "tplink");

    private static final String[] MULTI = {"cooler master", "lian li", "western digital", "team group", "tp-link",
            "tp link", "mars gaming", "be quiet", "id-cooling", "fractal design"};

    private static final String[] LIST = ("asus gigabyte aorus msi corsair logitech redragon adata xpg thermaltake "
            + "lenovo razer kingston hyperx sentey hp asrock 8bitdo genius aula gamemax raptor atk jbl dell evolabs "
            + "solarmax fantech zotac palit pny inno3d galax sapphire powercolor xfx nzxt deepcool aerocool antec "
            + "seasonic evga crucial samsung wd seagate hiksemi patriot teamgroup lexar sandisk acer apple xiaomi "
            + "tplink steelseries glorious noganet kolke trust havit marvo nintendo sony microsoft philips lg aoc "
            + "viewsonic benq elgato hikvision epson brother canon amog kimota kingdian bitfenix montech xigmatek "
            + "sharkoon cougar darkflash azza phanteks noctua thermalright netmak gadnic soundcore anker baseus "
            + "ugreen tenda mercusys huawei motorola").split(" ");

    private static final Set<String> SINGLE = Set.of(LIST);
    private static final Set<String> MULTI_SET = Set.of(MULTI);
    private static final Set<String> MULTI_FIRST = Arrays.stream(MULTI)
            .map(m -> m.split("[ -]")[0])
            .collect(Collectors.toSet());

    private NameBrands() {}

    /** Brands are whole [a-z0-9] runs (or two runs joined by one space/hyphen), so no regex is needed. */
    static Set<String> of(String nombre) {
        Set<String> out = new HashSet<>();
        int n = nombre.length();
        int i = 0;
        String previo = null;
        int previoIni = -1, previoFin = -1;
        while (i < n) {
            if (!alnum(nombre.charAt(i))) { i++; continue; }
            int a = i;
            while (i < n && alnum(nombre.charAt(i))) i++;
            String w = nombre.substring(a, i);
            if (SINGLE.contains(w)) out.add(ALIAS.getOrDefault(w, w));
            if (previo != null && a - previoFin == 1 && MULTI_FIRST.contains(previo)) {
                String junto = nombre.substring(previoIni, i);
                if (MULTI_SET.contains(junto)) out.add(ALIAS.getOrDefault(junto, junto));
            }
            previo = w;
            previoIni = a;
            previoFin = i;
        }
        return out;
    }

    private static boolean alnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
