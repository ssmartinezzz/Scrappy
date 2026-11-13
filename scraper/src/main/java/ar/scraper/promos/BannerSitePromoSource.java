package ar.scraper.promos;

import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.PromosDelSitio;
import ar.scraper.aggregator.text.AccentStripper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Platform-agnostic source: the home's banners plus, when the home links one, a "medios de pago" /
 * "promociones" page (at most {@value #MAX_PAGINAS_DE_PAGOS}, same host, no crawling). Bank names live
 * here, and often only in an image's alt text. On a payments page a line that names no medium is
 * dropped: "3 y 6 cuotas sin interés" under a bank heading would otherwise be filed as a generic card.
 */
public final class BannerSitePromoSource implements SitePromoSource {

    static final int MAX_PAGINAS_DE_PAGOS = 2;

    private static final Logger log = LoggerFactory.getLogger(BannerSitePromoSource.class);
    private static final Pattern PAGOS = Pattern.compile("(?:medios?|formas?) de pagos?");
    private static final Pattern OTRAS = Pattern.compile("promociones|bancos|financiaci|\\bcuotas\\b");

    @Override
    public PromosDelSitio leer(String baseUrl, PaginaWeb web, LocalDate hoy) {
        try {
            Document home = PaginasLeidas.leer(web, baseUrl);
            Set<PromoFinanciacion> promos = new LinkedHashSet<>(
                    parsear(TextoVisible.lineasDePromo(home), false, hoy));
            for (String url : enlacesDePagos(home, baseUrl)) {
                Document pagos = PaginasLeidas.leer(web, url);
                promos.addAll(parsear(TextoVisible.lineasDePromo(pagos), true, hoy));
            }
            return PromosDelSitio.leidas(new ArrayList<>(promos));
        } catch (RuntimeException e) {
            log.warn("[promos] lectura de {} fallida: {}", baseUrl, e.getMessage());
            return PromosDelSitio.NO_LEIDO;
        }
    }

    static List<PromoFinanciacion> parsear(List<String> lineas, boolean exigeMedioNombrado, LocalDate hoy) {
        List<PromoFinanciacion> out = new ArrayList<>();
        for (String linea : lineas) {
            if (exigeMedioNombrado && MediosDePago.encontrarEn(linea).isEmpty()) continue;
            out.addAll(PromoTextParser.parsear(linea, Alcance.SITIO, hoy));
        }
        return out;
    }

    /** Same-host links to a payments/promotions page, "medios de pago" first, home excluded. */
    static List<String> enlacesDePagos(Document home, String baseUrl) {
        String host = hostSinWww(baseUrl);
        Set<String> pagos = new LinkedHashSet<>();
        Set<String> otras = new LinkedHashSet<>();
        for (Element a : home.select("a[href]")) {
            String destino = destino(a.absUrl("href"), host, baseUrl);
            if (destino == null) continue;
            String clave = AccentStripper.strip((a.text() + " " + a.attr("href")).toLowerCase(Locale.ROOT))
                    .replace('-', ' ').replace('_', ' ');
            if (PAGOS.matcher(clave).find()) pagos.add(destino);
            else if (OTRAS.matcher(clave).find()) otras.add(destino);
        }
        pagos.addAll(otras);
        return pagos.stream().limit(MAX_PAGINAS_DE_PAGOS).toList();
    }

    private static String destino(String absoluta, String host, String baseUrl) {
        if (absoluta.isBlank()) return null;
        try {
            URI uri = URI.create(absoluta);
            String esquema = uri.getScheme();
            if (esquema == null || !(esquema.equals("http") || esquema.equals("https"))) return null;
            if (!host.equals(hostSinWww(absoluta))) return null;
            String sinFragmento = new URI(esquema, uri.getAuthority(), uri.getPath(), uri.getQuery(), null).toString();
            String home = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
            return sinFragmento.equals(baseUrl) || sinFragmento.equals(home) ? null : sinFragmento;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostSinWww(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }
}
