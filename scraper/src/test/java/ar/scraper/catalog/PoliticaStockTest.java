package ar.scraper.catalog;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PoliticaStock — one owner of the read-time stock rule")
class PoliticaStockTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");
    private final PoliticaStock politica =
            new PoliticaStock(Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7));

    private static Product producto(String url, Boolean enStock, Instant chequeado) {
        return Product.builder().sitio("S").nombre("N " + url).precio(1).url(url).talles(List.of())
                .enStock(enStock).stockChequeadoAt(chequeado).build();
    }

    @Test
    @DisplayName("estado uses the injected clock and vigencia")
    void estadoUsesClock() {
        assertThat(politica.estado(producto("a", true, AHORA.minus(Duration.ofDays(6))))).isEqualTo(EstadoStock.EN_STOCK);
        assertThat(politica.estado(producto("b", true, AHORA.minus(Duration.ofDays(8))))).isEqualTo(EstadoStock.DESCONOCIDO);
    }

    @Test
    @DisplayName("corte is now minus vigencia")
    void corte() {
        assertThat(politica.corte()).isEqualTo(AHORA.minus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("candidatos drops confirmed sold-out rows by default, keeps unknown and stale ones")
    void candidatosDropsOnlyConfirmedSoldOut() {
        Product enStock = producto("en", true, AHORA.minusSeconds(60));
        Product sin = producto("sin", false, AHORA.minusSeconds(60));
        Product sinViejo = producto("sin-viejo", false, AHORA.minus(Duration.ofDays(30)));
        Product desconocido = producto("desc", null, null);

        assertThat(politica.candidatos(List.of(enStock, sin, sinViejo, desconocido), false))
                .containsExactly(enStock, sinViejo, desconocido);
    }

    @Test
    @DisplayName("candidatos returns the same list when nothing is sold out (builders call it per request on the whole catalog)")
    void candidatosDoesNotCopyWhenNothingLeaves() {
        List<Product> catalogo = List.of(producto("en", true, AHORA.minusSeconds(60)),
                producto("sin-viejo", false, AHORA.minus(Duration.ofDays(30))), producto("desc", null, null));
        assertThat(politica.candidatos(catalogo, false)).isSameAs(catalogo);
    }

    @Test
    @DisplayName("candidatos keeps the boundary: a check exactly at the cutoff is still fresh")
    void candidatosBoundary() {
        Product enCorte = producto("corte", false, AHORA.minus(Duration.ofDays(7)));
        assertThat(politica.estado(enCorte)).isEqualTo(EstadoStock.SIN_STOCK);
        assertThat(politica.candidatos(List.of(enCorte), false)).isEmpty();
    }

    @Test
    @DisplayName("candidatos with incluirSinStock keeps everything")
    void candidatosOptIn() {
        Product sin = producto("sin", false, AHORA.minusSeconds(60));
        assertThat(politica.candidatos(List.of(sin), true)).containsExactly(sin);
    }

    /** A clock the test moves forward. */
    private static final class RelojMovil extends Clock {
        private Instant ahora;
        RelojMovil(Instant ahora) { this.ahora = ahora; }
        void avanzar(Duration d) { ahora = ahora.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @Test
    @DisplayName("candidatosDelCatalogo filters a snapshot once: the second request reuses the first result")
    void catalogoMemoizesPerSnapshot() {
        List<Product> catalogo = List.of(producto("en", true, AHORA.minusSeconds(60)),
                producto("sin", false, AHORA.minusSeconds(60)));
        List<Product> primero = politica.candidatosDelCatalogo(catalogo, false);
        assertThat(primero).extracting(Product::url).containsExactly("en");
        assertThat(politica.candidatosDelCatalogo(catalogo, false)).isSameAs(primero);
    }

    @Test
    @DisplayName("candidatosDelCatalogo drops the memo when a sold-out check expires: the row reads as unknown again")
    void catalogoMemoExpiresWithTheFirstSoldOutCheck() {
        RelojMovil reloj = new RelojMovil(AHORA);
        PoliticaStock movil = new PoliticaStock(reloj, Duration.ofDays(7));
        Product pronto = producto("pronto", false, AHORA.minus(Duration.ofDays(6)));
        Product tarde = producto("tarde", false, AHORA.minus(Duration.ofDays(1)));
        List<Product> catalogo = List.of(pronto, tarde);

        assertThat(movil.candidatosDelCatalogo(catalogo, false)).isEmpty();
        reloj.avanzar(Duration.ofDays(1));
        assertThat(movil.candidatosDelCatalogo(catalogo, false)).as("exactly at the cutoff it is still fresh").isEmpty();
        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(movil.candidatosDelCatalogo(catalogo, false)).containsExactly(pronto);
    }

    @Test
    @DisplayName("candidatosDelCatalogo recomputes for a new snapshot")
    void catalogoRecomputesForANewSnapshot() {
        Product sin = producto("sin", false, AHORA.minusSeconds(60));
        Product en = producto("en", true, AHORA.minusSeconds(60));
        assertThat(politica.candidatosDelCatalogo(List.of(sin, en), false)).containsExactly(en);
        assertThat(politica.candidatosDelCatalogo(List.of(en, sin, en), false)).containsExactly(en, en);
    }

    @Test
    @DisplayName("candidatosDelCatalogo with incluirSinStock returns the snapshot untouched")
    void catalogoOptIn() {
        List<Product> catalogo = List.of(producto("sin", false, AHORA.minusSeconds(60)));
        assertThat(politica.candidatosDelCatalogo(catalogo, true)).isSameAs(catalogo);
    }
}
