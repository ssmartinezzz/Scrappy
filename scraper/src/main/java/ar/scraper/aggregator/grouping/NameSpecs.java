package ar.scraper.aggregator.grouping;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Specs a product name states (sizes, capacities, model numbers...), taken from the lower-case,
 * accent-stripped name. A spec only vetoes a pair when BOTH names state it.
 */
final class NameSpecs {

    private record Unit(String dimension, double factor) {}

    private static final Map<String, Unit> UNITS = new HashMap<>();
    static {
        put("cap", 1024, "tb");
        put("cap", 1, "gb");
        put("capmb", 1, "mb");
        put("peso", 1000, "kg", "kgs", "kilo", "kilos");
        put("peso", 1, "gr", "grs", "g", "gramos");
        put("peso", 453.6, "lb", "lbs");
        put("vol", 1, "ml", "cc");
        put("vol", 1000, "l", "lt", "lts", "litro", "litros");
        put("vol", 29.57, "oz");
        put("pot", 1, "w", "watts");
        put("hz", 1, "hz");
        put("mhz", 1, "mhz");
        put("mah", 1, "mah");
        put("dpi", 1, "dpi");
        put("pulg", 1, "pulgadas", "\"", "pulg");
        put("uds", 1, "caps", "capsulas", "comprimidos", "tabletas", "softgels");
        put("serv", 1, "servicios", "servings", "porciones");
    }

    private static void put(String dimension, double factor, String... names) {
        for (String n : names) UNITS.put(n, new Unit(dimension, factor));
    }

    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final Pattern MILES = Pattern.compile("(?<![\\d.,])(\\d{1,3})\\.(\\d{3})(?![\\d.,])");
    private static final Pattern PULGADA_PEGADA = Pattern.compile("(?<![a-z0-9.,])" + NUM + "in(?![a-z0-9])");
    private static final Pattern VELOCIDAD = Pattern.compile(NUM + "\\s?([mg]b)\\s?/\\s?s(?![a-z0-9])");
    private static final Pattern MULTIPLO = Pattern.compile(
            "(?<![a-z0-9])(\\d+)\\s?x" + NUM + "\\s?(gb|tb)(?![a-z0-9])");
    private static final Pattern M2 = Pattern.compile("m\\.2|m2(?![a-z0-9])");
    private static final Pattern DIMENSION = Pattern.compile(
            "(?<![a-z0-9.])" + NUM + "\\s?x\\s?" + NUM + "(?:\\s?x\\s?" + NUM + ")?\\s?(mm|cm|m)?(?![a-z0-9])");
    private static final Pattern UNIDAD = Pattern.compile(NUM + "\\s?("
            + UNITS.keySet().stream()
                    .sorted((a, b) -> b.length() - a.length())
                    .map(Pattern::quote)
                    .collect(Collectors.joining("|"))
            + ")(?![a-z0-9])");
    private static final Pattern TALLE = Pattern.compile("talle\\s*(\\d{1,2}(?:[.,]5)?)");
    private static final Pattern TALLA = Pattern.compile(
            "(?<![a-z0-9.])(xxs|xs|s|m|l|xl|xxl|xxxl|2xl|3xl|4xl)(?![a-z0-9.])");
    private static final Pattern MODELO = Pattern.compile("(?<![a-z0-9.,])(\\d{3,5})(?![a-z0-9.,])");
    private static final Pattern CODIGO_CORTO = Pattern.compile("(?<![a-z0-9.])([a-z]\\d|\\d[a-z])(?![a-z0-9.])");
    private static final Pattern TIER = Pattern.compile("(?<![a-z0-9])(ti|super|xt|xtx)(?![a-z0-9])");

    final Map<String, Set<Double>> numeric = new HashMap<>();
    final Map<String, Set<String>> text = new HashMap<>();

    static NameSpecs of(String nombre) {
        NameSpecs s = new NameSpecs();
        String t = MILES.matcher(nombre).replaceAll("$1$2");
        t = PULGADA_PEGADA.matcher(t).replaceAll("$1\"");
        t = VELOCIDAD.matcher(t).replaceAll(" ");

        Matcher m = MULTIPLO.matcher(t);
        while (m.find()) {
            double factor = "tb".equals(m.group(3)) ? 1024 : 1;
            s.addNumber("cap", Integer.parseInt(m.group(1)) * parse(m.group(2)) * factor);
        }
        t = M2.matcher(MULTIPLO.matcher(t).replaceAll(" ")).replaceAll(" ");

        m = DIMENSION.matcher(t);
        while (m.find()) {
            double mul = switch (m.group(4) == null ? "" : m.group(4)) {
                case "mm" -> 1;
                case "cm" -> 10;
                case "m" -> 1000;
                default -> 0; // bare AxB is ambiguous (2x8, 3x fans)
            };
            if (mul == 0) continue;
            long a = Math.round(parse(m.group(1)) * mul);
            long b = Math.round(parse(m.group(2)) * mul);
            s.addText("dim", Math.min(a, b) + "," + Math.max(a, b));
        }
        t = DIMENSION.matcher(t).replaceAll(" ");

        m = UNIDAD.matcher(t);
        while (m.find()) {
            Unit u = UNITS.get(m.group(2));
            s.addNumber(u.dimension(), parse(m.group(1)) * u.factor());
        }
        t = UNIDAD.matcher(t).replaceAll(" ");

        m = TALLE.matcher(t);
        while (m.find()) s.addText("talle", m.group(1));
        m = TALLA.matcher(t);
        while (m.find()) s.addText("talla", m.group(1).replace("2xl", "xxl").replace("3xl", "xxxl"));
        m = MODELO.matcher(t);
        while (m.find()) s.addText("num", m.group(1));
        m = CODIGO_CORTO.matcher(t);
        while (m.find()) {
            String c = m.group(1);
            // Fan/pack counts ("x3", "4x") are unreliable across stores.
            if (c.charAt(0) == 'x' || (c.endsWith("x") && Character.isDigit(c.charAt(0))) || c.equals("m2")) continue;
            s.addText("cod2", c);
        }
        m = TIER.matcher(t);
        Set<String> tier = new HashSet<>();
        while (m.find()) tier.add(m.group(1));
        s.text.put("tier", tier.isEmpty() ? Set.of("-") : tier);
        return s;
    }

    boolean compatibleWith(NameSpecs o) {
        for (Map.Entry<String, Set<Double>> e : numeric.entrySet()) {
            Set<Double> other = o.numeric.get(e.getKey());
            if (other != null && !close(e.getValue(), other)) return false;
        }
        for (Map.Entry<String, Set<String>> e : text.entrySet()) {
            String key = e.getKey();
            Set<String> other = o.text.get(key);
            if (other == null || key.equals("tier")) continue;
            Set<String> mine = e.getValue();
            boolean ok = key.equals("num") || key.equals("cod2")
                    ? mine.containsAll(other) || other.containsAll(mine)
                    : !Collections.disjoint(mine, other);
            if (!ok) return false;
        }
        Set<String> n1 = text.get("num"), n2 = o.text.get("num");
        return n1 == null || n2 == null || text.get("tier").equals(o.text.get("tier"));
    }

    private static boolean close(Set<Double> a, Set<Double> b) {
        for (double x : a) {
            for (double y : b) {
                if (x == y || Math.abs(x - y) <= 0.04 * Math.max(x, y)) return true;
            }
        }
        return false;
    }

    private void addNumber(String key, double v) {
        numeric.computeIfAbsent(key, k -> new HashSet<>()).add(Math.round(v * 10) / 10.0);
    }

    private void addText(String key, String v) {
        text.computeIfAbsent(key, k -> new HashSet<>()).add(v);
    }

    private static double parse(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }
}
