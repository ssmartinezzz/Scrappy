package ar.scraper.pages;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Fullh4rd product detail page: cash price, list price, the Mercado Pago installment table
 * and the stock banners. Pure: it never fetches, and a missing block yields empty plans, not an error.
 */
public final class FullH4rdFinanciacion {

    private static final String MODAL = "#mercadopago-cuotas-modal";
    private static final Pattern CUOTAS = Pattern.compile("(\\d+)\\s*cuotas?", Pattern.CASE_INSENSITIVE);

    /** {@code aproximado}: the site prefixes the amount with "≈". {@code medio}: null when the page names none. */
    public record Plan(int cuotas, double montoCuota, double total, boolean aproximado, String medio,
                       boolean sinInteres) { }

    /** Prices are null when the page does not show them; stock texts are the raw banner headings. */
    public record Detalle(Double precioContado, Double precioLista, String stockWeb, String stockLocal,
                          List<Plan> planes) { }

    private FullH4rdFinanciacion() { }

    public static Detalle parse(String html) {
        Document doc = Jsoup.parse(StringUtils.defaultString(html));
        Double contado = precioEspecial(doc);
        Double lista = precio(doc.selectFirst(MODAL + " header h3"));
        return new Detalle(contado, lista, stock(doc, "web"), stock(doc, "local"), planes(doc, contado));
    }

    private static Double precioEspecial(Document doc) {
        Element etiqueta = doc.selectFirst("span:containsOwn(Precio especial)");
        return etiqueta == null ? null : precio(etiqueta.parent().selectFirst("span.bold"));
    }

    private static Double precio(Element el) {
        return el == null ? null : TechStorePage.parsePrecioTech(el.text()).orElse(null);
    }

    private static String stock(Document doc, String donde) {
        Element h = doc.selectFirst("h5:matchesOwn((?i)\\bstock\\b.*\\b" + donde + "\\b)");
        return h == null ? null : h.text().trim();
    }

    private static List<Plan> planes(Document doc, Double contado) {
        Element img = doc.selectFirst("button[popovertarget=mercadopago-cuotas-modal] img[alt]");
        String medio = img == null ? null : StringUtils.trimToNull(img.attr("alt"));
        List<Plan> planes = new ArrayList<>();
        for (Element fila : doc.select(MODAL + " tr")) {
            var celdas = fila.select("td");
            Matcher n = celdas.size() < 2 ? null : CUOTAS.matcher(celdas.get(0).text());
            Double monto = celdas.size() < 2 ? null : precio(celdas.get(1));
            if (n != null && n.find() && monto != null) {
                planes.add(plan(Integer.parseInt(n.group(1)), monto, celdas.get(1).text().contains("≈"),
                        medio, contado));
            }
        }
        return List.copyOf(planes);
    }

    /**
     * Interest-free when the total does not exceed the cash price. Each installment is rounded to the
     * peso, so the total may drift half a peso per installment without meaning interest.
     */
    private static Plan plan(int cuotas, double monto, boolean aproximado, String medio, Double contado) {
        double total = cuotas * monto;
        boolean sinInteres = contado != null && total <= contado + cuotas * 0.5;
        return new Plan(cuotas, monto, total, aproximado, medio, sinInteres);
    }
}
