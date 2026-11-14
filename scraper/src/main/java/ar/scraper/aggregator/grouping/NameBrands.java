package ar.scraper.aggregator.grouping;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private static final Pattern PATTERN = Pattern.compile("(?<![a-z0-9])("
            + Stream.concat(Arrays.stream(MULTI), Arrays.stream(LIST))
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote)
                    .collect(Collectors.joining("|"))
            + ")(?![a-z0-9])");

    private NameBrands() {}

    static Set<String> of(String nombre) {
        Set<String> out = new HashSet<>();
        Matcher m = PATTERN.matcher(nombre);
        while (m.find()) out.add(ALIAS.getOrDefault(m.group(1), m.group(1)));
        return out;
    }
}
