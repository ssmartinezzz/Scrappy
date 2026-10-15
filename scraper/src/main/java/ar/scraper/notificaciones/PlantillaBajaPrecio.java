package ar.scraper.notificaciones;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rendering only: no mail transport, no persistence. */
@Component
public class PlantillaBajaPrecio {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Pattern SALTOS = Pattern.compile("[\\p{Cc}\\u2028\\u2029]+");
    private static final DecimalFormatSymbols ES_AR = DecimalFormatSymbols.getInstance(Locale.of("es", "AR"));

    private final String layout = leer("mail/baja-precio.html");
    private final String itemHtml = leer("mail/baja-precio-item.html");
    private final String enlaceAvisos;

    /** {@code baseFrontend} is the public origin of the web app; the footer links to where the opt-out lives. */
    public PlantillaBajaPrecio(@Value("${password.reset.link-base}") String baseFrontend) {
        String origen = StringUtils.removeEnd(enlaceSeguro(baseFrontend), "/");
        this.enlaceAvisos = "#".equals(origen) ? "" : origen + "/favoritos";
    }

    public CorreoRenderizado renderizar(List<BajaReclamada> bajas) {
        return renderizar(bajas, null);
    }

    /** {@code enlaceBaja} is the one-click unsubscribe URL; null or non-http(s) leaves the line out. */
    public CorreoRenderizado renderizar(List<BajaReclamada> bajas, String enlaceBaja) {
        List<BajaReclamada> ordenadas = bajas.stream()
                .sorted(Comparator.comparingDouble(PlantillaBajaPrecio::porcentajeDeBaja).reversed())
                .toList();
        String asunto = "Bajó de precio: " + ordenadas.size()
                + (ordenadas.size() == 1 ? " favorito" : " favoritos");
        StringBuilder items = new StringBuilder();
        StringBuilder texto = new StringBuilder(asunto).append("\n\n");
        for (BajaReclamada b : ordenadas) {
            items.append(sustituir(itemHtml, Map.of(
                    "nombre", HtmlUtils.htmlEscape(b.nombre()),
                    "sitio", HtmlUtils.htmlEscape(b.sitio()),
                    "anterior", HtmlUtils.htmlEscape(pesos(b.precioAnterior())),
                    "nuevo", HtmlUtils.htmlEscape(pesos(b.precioNuevo())),
                    "porcentaje", String.valueOf(Math.round(porcentajeDeBaja(b))),
                    "url", HtmlUtils.htmlEscape(enlaceSeguro(b.url())))));
            texto.append("- ").append(enUnaLinea(b.nombre())).append(" (").append(enUnaLinea(b.sitio())).append(")\n  ")
                    .append(pesos(b.precioAnterior())).append(" -> ").append(pesos(b.precioNuevo()))
                    .append(" (-").append(Math.round(porcentajeDeBaja(b))).append("%)\n  ")
                    .append(enUnaLinea(enlaceSeguro(b.url()))).append("\n\n");
        }
        String pieHtml = enlaceAvisos.isEmpty() ? "" : " Podés desactivar estos avisos desde <a href=\""
                + HtmlUtils.htmlEscape(enlaceAvisos) + "\" style=\"color:#71717a;\">tus favoritos</a>.";
        String baja = enlaceSeguro(enlaceBaja);
        boolean conBaja = !"#".equals(baja);
        String pieBajaHtml = conBaja ? " También podés <a href=\"" + HtmlUtils.htmlEscape(baja)
                + "\" style=\"color:#71717a;\">darte de baja con un clic</a>." : "";
        String html = sustituir(layout, Map.of("asunto", HtmlUtils.htmlEscape(asunto), "items", items.toString(),
                "pieAvisos", pieHtml + pieBajaHtml));
        String pieTexto = (enlaceAvisos.isEmpty() ? "" : "\n\nPara dejar de recibir estos avisos: " + enlaceAvisos)
                + (conBaja ? "\n\nDarte de baja con un clic: " + baja : "");
        return new CorreoRenderizado(asunto, html, texto.toString().stripTrailing() + pieTexto + "\n");
    }

    /** Scraped text in a line-oriented part: a line break would let it start a line of its own. */
    private static String enUnaLinea(String valor) {
        return SALTOS.matcher(StringUtils.defaultString(valor)).replaceAll(" ");
    }

    private static double porcentajeDeBaja(BajaReclamada b) {
        return (b.precioAnterior() - b.precioNuevo()) / b.precioAnterior() * 100;
    }

    static String pesos(double monto) {
        DecimalFormat formato = new DecimalFormat("#,##0", ES_AR);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return "$ " + formato.format(monto);
    }

    /** A product URL is scraped data; only http(s) may become a link. */
    private static String enlaceSeguro(String url) {
        String limpia = StringUtils.strip(StringUtils.defaultString(url));
        return StringUtils.startsWithAny(limpia.toLowerCase(Locale.ROOT), "http://", "https://") ? limpia : "#";
    }

    /** One pass over the template, so a value that looks like a placeholder is never substituted again. */
    private static String sustituir(String plantilla, Map<String, String> valores) {
        Matcher m = PLACEHOLDER.matcher(plantilla);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(valores.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String leer(String recurso) {
        try (var in = PlantillaBajaPrecio.class.getClassLoader().getResourceAsStream(recurso)) {
            if (in == null) throw new IllegalStateException("falta el recurso " + recurso);
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
