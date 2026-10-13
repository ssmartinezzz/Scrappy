package ar.scraper.pcs.specs;

import java.util.List;
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

    private static final Pattern FAMILIA_PEGADA = Pattern.compile(" (rtx|gtx|rx|gt)(\\d)");
    private static final Pattern SUFIJO_PEGADO = Pattern.compile(" (\\d{3,4})(ti|super|xtx|xt|gre) ");

    private static final List<Pattern> GPU = List.of(
            Pattern.compile(" (rtx) ([2-9]\\d{3})((?: ti)?(?: super)?) "),
            Pattern.compile(" (gtx) (\\d{3,4})((?: ti)?(?: super)?) "),
            Pattern.compile(" (gt) (\\d{3,4})((?: ti)?) "),
            Pattern.compile(" (rx) ([45]\\d{2}|[5-9]\\d{3})((?: xtx| xt| gre)?) "),
            Pattern.compile(" (arc) ([ab]\\d{3})() "));

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

    static String gpu(Tokens tokens) {
        String texto = FAMILIA_PEGADA.matcher(tokens.padded()).replaceAll(" $1 $2");
        texto = SUFIJO_PEGADO.matcher(texto).replaceAll(" $1 $2 ");
        for (Pattern familia : GPU) {
            Matcher m = familia.matcher(texto);
            if (!m.find()) continue;
            String sufijo = m.group(3).trim().replace(' ', '-');
            // "Rx9070 Oc 12gb Gre": la GRE es el único sufijo que aparece suelto lejos del número.
            if (sufijo.isEmpty() && m.group(1).equals("rx") && tokens.has("gre")) sufijo = "gre";
            return m.group(1) + "-" + m.group(2) + (sufijo.isEmpty() ? "" : "-" + sufijo);
        }
        return "";
    }
}
