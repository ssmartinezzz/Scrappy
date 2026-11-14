package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Captured storefront pages (fixtures/site-promos) served through the {@link PaginaWeb} seam. */
final class SitePromoFixtures {

    private static final Path DIR = Path.of("src/test/resources/fixtures/site-promos");

    private SitePromoFixtures() {}

    static String fixture(String nombre) {
        try {
            return Files.readString(DIR.resolve(nombre), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Serves the given url -> html; any other url fails like a 404 would. Records every request. */
    static final class Web implements PaginaWeb {
        final List<String> pedidos = new ArrayList<>();
        private final Map<String, String> paginas;

        Web(Map<String, String> paginas) {
            this.paginas = paginas;
        }

        @Override
        public String html(String url) {
            pedidos.add(url);
            String html = paginas.get(url);
            if (html == null) throw new LecturaFallidaException("HTTP 404 " + url);
            return html;
        }
    }

    /** medio[|red]|tipo|cuotas|pct; the red segment is absent when the promo names no network. */
    static String resumen(PromoFinanciacion p) {
        return p.medioPago() + (p.redMedioPago() == null ? "" : "|" + p.redMedioPago()) + "|" + p.tipo() + "|" + p.cuotas() + "|" + p.pct();
    }
}
