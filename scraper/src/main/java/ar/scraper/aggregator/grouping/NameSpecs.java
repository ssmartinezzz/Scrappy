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
        put("pulg", 1, "pulgadas", "\"", "''", "\u201d", "pulg");
        put("ghz", 1, "ghz");
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
            "(?<![a-z0-9.])" + NUM + "\\s?x\\s?" + NUM + "(?:\\s?x\\s?" + NUM + ")?\\s?(mm|cm|m)?(?![a-z0-9]|[.,]\\d)");
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
    private static final Pattern TALLE_COMPLETO = Pattern.compile("talle\\s*\\d+");
    private static final Pattern RUIDO_VERSION = Pattern.compile("sata\\s*(?:iii|ii|3)|80\\s*\\+|80\\s*plus|usb\\s*\\d(?:\\.\\d)?"
            + "|pcie\\s*\\d(?:\\.\\d)?|atx\\s*\\d(?:\\.\\d)?|gen\\s*\\d|ddr\\d|gddr\\d|wifi\\s*\\d"
            + "|bluetooth\\s*\\d(?:\\.\\d)?|\\d+(?:[.,]\\d+)?\\s*\"");
    private static final Pattern ROMANO = Pattern.compile("(?<![a-z0-9])(viii|vii|iii|ii|iv|vi|ix)(?![a-z0-9])");
    private static final Map<String, String> ROMANOS = Map.of(
            "ii", "2", "iii", "3", "iv", "4", "vi", "6", "vii", "7", "viii", "8", "ix", "9");
    private static final Pattern VERSION = Pattern.compile("(?<![a-z0-9.,])(\\d{1,2})(?:[.,](\\d))?(?![a-z0-9]|[.,]\\d)");
    private static final Pattern PALABRA = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> EDICIONES = Set.of("elite", "pro", "academy", "club", "league", "lite",
            "plus", "max", "mini", "ultra", "base", "alpha", "platform", "se");
    private static final Pattern SUELA = Pattern.compile("(?<![a-z0-9])(fg|tf|ag|mg|sg|ic)(?![a-z0-9])");
    private static final Map<String, String> SUELA_SINONIMOS = Map.of(
            "cancha cubierta", "ic", "futsal", "ic", "sala", "ic",
            "pasto artificial", "tf", "pasto sintetico", "tf", "multitapon", "tf");
    private static final Pattern SUELA_SINONIMO = Pattern.compile("(?<![a-z0-9])("
            + String.join("|", SUELA_SINONIMOS.keySet()) + ")(?![a-z0-9])");
    private static final Pattern IN_FINAL = Pattern.compile("(?<![a-z0-9])in\\s*$");
    private static final Pattern TIER = Pattern.compile("(?<![a-z0-9])(ti|super|xt|xtx)(?![a-z0-9])");

    final Map<String, Set<Double>> numeric = new HashMap<>();
    final Map<String, Set<String>> text = new HashMap<>();
    final Set<String> versions = new HashSet<>();
    final Set<String> editions = new HashSet<>();

    // A unit is always a whole letter run (or a quote mark) followed by a non-alphanumeric.
    private static final Pattern UNIDAD_RAPIDA = Pattern.compile(NUM + "\\s?([a-z]+|''|\"|\u201d)(?![a-z0-9])");
    private static final Set<String> TALLAS = Set.of("xxs", "xs", "s", "m", "l", "xl", "xxl", "xxxl", "2xl", "3xl", "4xl");
    private static final Set<String> TIERS = Set.of("ti", "super", "xt", "xtx");
    private static final Set<String> SUELAS = Set.of("fg", "tf", "ag", "mg", "sg", "ic");
    private static final String[] TRIGGERS_RUIDO = {"sata", "80", "usb", "pcie", "atx", "gen", "ddr", "wifi", "bluetooth", "\""};

    static NameSpecs of(String nombre) {
        NameSpecs s = new NameSpecs();
        String t = nombre;
        if (t.indexOf('.') >= 0) t = reemplazar(MILES, t, "$1$2");
        if (t.contains("in")) t = reemplazar(PULGADA_PEGADA, t, "$1\"");
        if (t.indexOf('/') >= 0) t = reemplazar(VELOCIDAD, t, " ");

        boolean hayX = t.indexOf('x') >= 0;
        if (hayX) {
            Matcher m = MULTIPLO.matcher(t);
            boolean hubo = false;
            while (m.find()) {
                hubo = true;
                double factor = "tb".equals(m.group(3)) ? 1024 : 1;
                s.addNumber("cap", Integer.parseInt(m.group(1)) * parse(m.group(2)) * factor);
            }
            if (hubo) t = m.reset().replaceAll(" ");
        }
        if (t.contains("m2") || t.contains("m.2")) t = reemplazar(M2, t, " ");

        if (hayX) {
            Matcher m = DIMENSION.matcher(t);
            boolean hubo = false;
            while (m.find()) {
                hubo = true;
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
            if (hubo) t = m.reset().replaceAll(" ");
        }

        if (hayDigito(t)) {
            Matcher m = UNIDAD_RAPIDA.matcher(t);
            StringBuilder sb = null;
            int last = 0;
            while (m.find()) {
                Unit u = UNITS.get(m.group(2));
                if (u == null) continue;
                s.addNumber(u.dimension(), parse(m.group(1)) * u.factor());
                if (sb == null) sb = new StringBuilder();
                sb.append(t, last, m.start()).append(' ');
                last = m.end();
            }
            if (sb != null) t = sb.append(t, last, t.length()).toString();
        }

        s.versions.addAll(versiones(t));
        if (t.contains("talle")) {
            Matcher m = TALLE.matcher(t);
            while (m.find()) s.addText("talle", m.group(1));
        }

        Set<String> tier = new HashSet<>();
        if (t.equals(nombre)) {
            s.recorrer(t, true, true, tier);
        } else {
            s.recorrer(nombre, true, false, tier);
            s.recorrer(t, false, true, tier);
        }
        if (nombre.contains("cancha ") || nombre.contains("pasto ")) {
            Matcher m = SUELA_SINONIMO.matcher(nombre);
            while (m.find()) s.addText("suela", SUELA_SINONIMOS.get(m.group(1)));
        }
        s.text.put("tier", tier.isEmpty() ? Set.of("-") : tier);
        return s;
    }

    /**
     * One pass over the maximal [a-z0-9] runs of a text. Every word-shaped pattern here only ever
     * matched a whole run, so a set lookup per run is equivalent to the regexes it replaces.
     * {@code enNombre}: editions, soles and the trailing "in"; {@code enTexto}: sizes, tier, model
     * numbers and short codes (which also refuse a '.' or ',' neighbour).
     */
    private void recorrer(String x, boolean enNombre, boolean enTexto, Set<String> tier) {
        int n = x.length();
        int i = 0;
        int ultimoIni = -1, ultimoFin = -1;
        while (i < n) {
            if (!alnum(x.charAt(i))) { i++; continue; }
            int a = i;
            while (i < n && alnum(x.charAt(i))) i++;
            int b = i;
            ultimoIni = a;
            ultimoFin = b;
            int len = b - a;
            if (len > 10) continue;
            String w = x.substring(a, b);
            if (enNombre) {
                if (EDICIONES.contains(w)) editions.add(w);
                if (SUELAS.contains(w)) addText("suela", w);
                else if (w.equals("futsal") || w.equals("sala")) addText("suela", "ic");
                else if (w.equals("multitapon")) addText("suela", "tf");
            }
            if (enTexto) {
                if (TIERS.contains(w)) tier.add(w);
                char antes = a > 0 ? x.charAt(a - 1) : ' ';
                char despues = b < n ? x.charAt(b) : ' ';
                boolean punto = antes == '.' || despues == '.';
                if (len <= 4 && !punto && TALLAS.contains(w)) {
                    addText("talla", w.replace("2xl", "xxl").replace("3xl", "xxxl"));
                }
                if (len >= 3 && len <= 5 && !punto && antes != ',' && despues != ',' && soloDigitos(w)) {
                    addText("num", w);
                }
                if (len == 2 && !punto && esCodigoCorto(w)) {
                    // Fan/pack counts ("x3", "4x") are unreliable across stores.
                    char c0 = w.charAt(0);
                    if (c0 != 'x' && !(w.charAt(1) == 'x' && Character.isDigit(c0)) && !w.equals("m2")) addText("cod2", w);
                }
            }
        }
        // A spaced "in" is a sole only when it ends the name; "2 in 1" is not.
        if (enNombre && ultimoIni >= 0 && ultimoFin - ultimoIni == 2 && x.startsWith("in", ultimoIni)
                && IN_FINAL.matcher(x).find()) {
            addText("suela", "ic");
        }
    }

    private static boolean alnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean soloDigitos(String w) {
        for (int i = 0; i < w.length(); i++) if (w.charAt(i) < '0' || w.charAt(i) > '9') return false;
        return true;
    }

    private static boolean esCodigoCorto(String w) {
        char a = w.charAt(0), b = w.charAt(1);
        boolean aLetra = a >= 'a' && a <= 'z', bLetra = b >= 'a' && b <= 'z';
        boolean aDigito = a >= '0' && a <= '9', bDigito = b >= '0' && b <= '9';
        return (aLetra && bDigito) || (aDigito && bLetra);
    }

    private static boolean hayDigito(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    /** Like replaceAll, but returns the same instance (no copy) when nothing matches. */
    private static String reemplazar(Pattern p, String t, String reemplazo) {
        Matcher m = p.matcher(t);
        return m.find() ? m.replaceAll(reemplazo) : t;
    }

    private static boolean contieneAlguno(String t, String[] needles) {
        for (String n : needles) if (t.contains(n)) return true;
        return false;
    }

    private static Set<String> versiones(String t) {
        if (t.contains("talle")) t = reemplazar(TALLE_COMPLETO, t, " ");
        if (contieneAlguno(t, TRIGGERS_RUIDO)) t = reemplazar(RUIDO_VERSION, t, " ");
        if (t.contains("ii") || t.contains("iv") || t.contains("vi") || t.contains("ix")) {
            Matcher r = ROMANO.matcher(t);
            if (r.find()) t = r.replaceAll(x -> " " + ROMANOS.get(x.group(1)) + " ");
        }
        if (!hayDigito(t)) return Set.of();
        Set<String> out = new HashSet<>();
        Matcher m = VERSION.matcher(t);
        while (m.find()) {
            String d = m.group(2);
            out.add(d == null || d.equals("0") ? m.group(1) : m.group(1) + "." + d);
        }
        return out;
    }

    /** True when the name states a model number or version, so it is more than brand + category. */
    boolean hasModel() {
        return !versions.isEmpty() || text.containsKey("num") || text.containsKey("cod2");
    }

    private Set<String> versionsAndModels() {
        Set<String> all = new HashSet<>(versions);
        Set<String> num = text.get("num");
        if (num != null) all.addAll(num);
        return all;
    }

    private static boolean nested(Set<String> a, Set<String> b) {
        return a.isEmpty() || b.isEmpty() || a.containsAll(b) || b.containsAll(a);
    }

    boolean compatibleWith(NameSpecs o) {
        if (!nested(versions, o.versions) || !nested(versionsAndModels(), o.versionsAndModels())) return false;
        if (!editions.isEmpty() && !o.editions.isEmpty() && !editions.equals(o.editions)) return false;
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
