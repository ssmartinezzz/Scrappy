package ar.scraper.pcs.specs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The canonical model key a benchmark table is looked up by: family, tier and model number WITH its
 * suffix ({@code ti}, {@code xt}, {@code x3d}, {@code f}…), because the suffix is exactly what the
 * gama/nivel ladder cannot tell apart. Contract shared with {@code scripts/benchmarks} through the
 * golden fixture {@code pcs/benchmarks/claves.csv}.
 */
final class ClaveDeModelo {

    private ClaveDeModelo() {
    }

    private static final Pattern RYZEN = Pattern.compile(" ryzen ([3579]) ((?:pro )?)(\\d{4}[a-z0-9]*) ");
    private static final Pattern CORE_I = Pattern.compile(" i([3579]) (\\d{4,5}[a-z0-9]*) ");
    private static final Pattern CORE_ULTRA = Pattern.compile(" ultra ([3579]) (\\d{3}[a-z]*) ");

    private static final List<String> FAMILIAS_PEGABLES = List.of("rtx", "gtx", "rx", "gt");
    private static final Set<String> SUFIJOS_PEGABLES = Set.of("ti", "super", "xtx", "xt", "gre");
    private static final Set<String> SUFIJOS_RX = Set.of("xtx", "xt", "gre");
    private static final List<String> FAMILIAS_GPU = List.of("rtx", "gtx", "gt", "rx", "arc");

    static String cpu(Tokens tokens) {
        String padded = tokens.padded();
        Matcher ryzen = RYZEN.matcher(padded);
        if (ryzen.find()) return "ryzen-" + ryzen.group(1) + "-" + ryzen.group(2).replace(' ', '-') + ryzen.group(3);
        Matcher ultra = CORE_ULTRA.matcher(padded);
        if (ultra.find()) return "core-ultra-" + ultra.group(1) + "-" + ultra.group(2);
        Matcher core = CORE_I.matcher(padded);
        if (core.find()) return "core-i" + core.group(1) + "-" + core.group(2);
        return "";
    }

    /** Families are tried in a fixed order, each at its first occurrence: "GT 710 ... RTX 3060" is an RTX. */
    static String gpu(Tokens tokens) {
        List<String> t = despegados(tokens.array());
        for (String familia : FAMILIAS_GPU) {
            for (int i = 0; i + 1 < t.size(); i++) {
                if (!t.get(i).equals(familia) || !esNumeroDe(familia, t.get(i + 1))) continue;
                String sufijo = sufijo(familia, t, i + 2);
                // "Rx9070 Oc 12gb Gre": la GRE es el único sufijo que aparece suelto lejos del número.
                if (sufijo.isEmpty() && familia.equals("rx") && tokens.has("gre")) sufijo = "gre";
                return familia + "-" + t.get(i + 1) + (sufijo.isEmpty() ? "" : "-" + sufijo);
            }
        }
        return "";
    }

    /**
     * "Rtx5070" → rtx 5070, "5060ti" → 5060 ti. A glued suffix right after another split one stays
     * glued, as the regex reading this replaced did, so every key stays byte-identical.
     */
    private static List<String> despegados(String[] tokens) {
        List<String> sinFamiliaPegada = new ArrayList<>(tokens.length + 2);
        for (String t : tokens) {
            String familia = familiaPegada(t);
            if (familia.isEmpty()) {
                sinFamiliaPegada.add(t);
            } else {
                sinFamiliaPegada.add(familia);
                sinFamiliaPegada.add(t.substring(familia.length()));
            }
        }
        List<String> out = new ArrayList<>(sinFamiliaPegada.size() + 2);
        boolean anteriorPartido = false;
        for (String t : sinFamiliaPegada) {
            int corte = anteriorPartido ? -1 : corteDeSufijo(t);
            anteriorPartido = corte > 0;
            if (anteriorPartido) {
                out.add(t.substring(0, corte));
                out.add(t.substring(corte));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    private static String familiaPegada(String t) {
        for (String familia : FAMILIAS_PEGABLES) {
            if (t.length() > familia.length() && t.startsWith(familia)
                    && Character.isDigit(t.charAt(familia.length()))) {
                return familia;
            }
        }
        return "";
    }

    private static int corteDeSufijo(String t) {
        int digitos = digitosIniciales(t);
        return (digitos == 3 || digitos == 4) && SUFIJOS_PEGABLES.contains(t.substring(digitos)) ? digitos : -1;
    }

    private static boolean esNumeroDe(String familia, String t) {
        int n = t.length();
        return switch (familia) {
            case "rtx" -> n == 4 && digitosIniciales(t) == 4 && t.charAt(0) >= '2';
            case "gtx", "gt" -> (n == 3 || n == 4) && digitosIniciales(t) == n;
            case "rx" -> digitosIniciales(t) == n
                    && ((n == 3 && (t.charAt(0) == '4' || t.charAt(0) == '5')) || (n == 4 && t.charAt(0) >= '5'));
            case "arc" -> n == 4 && (t.charAt(0) == 'a' || t.charAt(0) == 'b') && digitosIniciales(t.substring(1)) == 3;
            default -> false;
        };
    }

    private static String sufijo(String familia, List<String> t, int desde) {
        int i = desde;
        switch (familia) {
            case "rtx", "gtx" -> {
                String sufijo = "";
                if (i < t.size() && t.get(i).equals("ti")) {
                    sufijo = "ti";
                    i++;
                }
                if (i < t.size() && t.get(i).equals("super")) sufijo = sufijo.isEmpty() ? "super" : sufijo + "-super";
                return sufijo;
            }
            case "gt" -> {
                return i < t.size() && t.get(i).equals("ti") ? "ti" : "";
            }
            case "rx" -> {
                return i < t.size() && SUFIJOS_RX.contains(t.get(i)) ? t.get(i) : "";
            }
            default -> {
                return "";
            }
        }
    }

    private static int digitosIniciales(String t) {
        int i = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        return i;
    }
}
