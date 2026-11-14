package ar.scraper.promos;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Visible text of a page as one line per block, plus the {@code alt}/{@code title} of images (bank
 * promos are often a picture whose alt text is the only signal). Only lines that look like a payment
 * promo survive, and anything inside a product card or shelf is skipped: "3 cuotas sin interés de
 * $20.000" on a card is that product's price line, not a store-wide promo.
 */
final class TextoVisible {

    private static final int LINEA_MAX = 400;
    private static final Set<String> IGNORADOS = Set.of("script", "style", "noscript", "template", "svg",
            "select", "option", "head", "iframe");
    private static final Set<String> EN_LINEA = Set.of("a", "b", "strong", "em", "i", "u", "small", "sup", "sub",
            "font", "mark", "abbr");
    private static final Pattern DE_PRODUCTO = Pattern.compile("product|shelf|vitrina|item-grid",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENAL = Pattern.compile("cuota|%|reintegro|descuento|\\boff\\b|transferencia",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ESPACIOS = Pattern.compile("[\\s\\u00A0]+");

    private TextoVisible() {}

    static List<String> lineasDePromo(Document doc) {
        StringBuilder sb = new StringBuilder();
        recorrer(doc.body() == null ? doc : doc.body(), sb);
        Set<String> out = new LinkedHashSet<>();
        for (String crudo : sb.toString().split("\n")) {
            String linea = ESPACIOS.matcher(crudo).replaceAll(" ").strip();
            if (!linea.isEmpty() && linea.length() <= LINEA_MAX && SENAL.matcher(linea).find()) out.add(linea);
        }
        return new ArrayList<>(out);
    }

    private static void recorrer(Node nodo, StringBuilder sb) {
        if (nodo instanceof TextNode t) {
            sb.append(t.getWholeText());
            return;
        }
        if (!(nodo instanceof Element el)) {
            nodo.childNodes().forEach(h -> recorrer(h, sb));
            return;
        }
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        if (IGNORADOS.contains(tag) || esDeProducto(el)) return;
        if ("img".equals(tag)) {
            for (String atributo : new String[] {"alt", "title"}) {
                if (StringUtils.isNotBlank(el.attr(atributo))) sb.append('\n').append(el.attr(atributo)).append('\n');
            }
            return;
        }
        boolean enLinea = EN_LINEA.contains(tag);
        if (!enLinea) sb.append('\n');
        if ("br".equals(tag)) return;
        el.childNodes().forEach(h -> recorrer(h, sb));
        if (!enLinea) sb.append('\n');
    }

    private static boolean esDeProducto(Element el) {
        return DE_PRODUCTO.matcher(el.className()).find() || el.attr("itemtype").contains("Product");
    }
}
