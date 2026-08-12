package ar.scraper.db;

import ar.scraper.aggregator.text.PrecioParser;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 4 (V17, design DD7) — the dangerous
 * migration's live verification layer.
 *
 * <p>The project's known failure mode is silence: a bad bind inside
 * {@code sp_upsert_run} does not surface as a thrown error, it rolls back the
 * whole batch and {@code ProductRepository.upsertProductos}'s
 * {@code catch (Exception)} swallows it, returning
 * {@code UpsertStats(0,0,0,0)}. That is why {@code UpsertStats.nuevos == 3}
 * is asserted FIRST, before any column value: {@code 0} is the exact
 * signature of a rolled-back upsert, and asserting the column alone would let
 * a totally failed upsert pass as "no rows, no mismatch".</p>
 *
 * <p>By this point in the change {@code Product.precioOriginal} is already a
 * {@code Double} (Phase 2/DD1) — {@link PrecioParser} already ran in Java at
 * scrape time, so an "unparseable" original price can never reach this
 * round-trip as a raw string; it is indistinguishable, end to end, from
 * "there was no original price at all". The third case below makes that
 * traceable: it is built by literally running the string a scraper would
 * have handed {@code PrecioParser} through {@code parse(...).orElse(null)},
 * documenting that the garbage-in became {@code null} upstream of the DB,
 * not a special case the SQL layer has to know about.</p>
 */
@Epic("Persistence")
@Feature("Price normalization")
@Story("precio_orig round-trips through sp_upsert_run as a real double")
@DisplayName("V17 — sp_upsert_run precio_orig round-trip")
class SpUpsertRunPrecioOrigRoundTripTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, Double precioOrig) {
        return new Product("Sitio", "Producto", 1000.0, precioOrig, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    @DisplayName("3 productos (parseable / sin precio original / no parseó en el scrape) upsertean juntos, sin swallow")
    void tresProductosRoundTripeanSinSwallow() throws Exception {
        String urlParseable = "https://site.com/precio-orig-parseable";
        String urlSinOriginal = "https://site.com/precio-orig-ausente";
        String urlNoParseoEnElScrape = "https://site.com/precio-orig-no-parseo";

        Double parseable = 45000.0;
        Double sinOriginal = null;
        // Lo que un scraper hubiese escrito si el string crudo nunca parseó:
        // PrecioParser ya corrió y decidió "sin opinión" (CODE-5) antes de
        // que este Product exista.
        Double noParseoEnElScrape = PrecioParser.parse("no es un precio").isPresent()
                ? PrecioParser.parse("no es un precio").getAsDouble() : null;
        assertThat(noParseoEnElScrape).as("fixture assumption: este string no parsea").isNull();

        var stats = db.upsertProductos(List.of(
                producto(urlParseable, parseable),
                producto(urlSinOriginal, sinOriginal),
                producto(urlNoParseoEnElScrape, noParseoEnElScrape)
        ));

        // (a) — el swallow-catcher. 0 es la firma exacta de un upsert que
        // rollbackeó completo; asertarlo PRIMERO es lo que hace audible el
        // silencio del catch(Exception) upstream.
        assertThat(stats.nuevos())
                .as("los 3 productos se insertaron — 0 significaría que sp_upsert_run rollbackeó todo el batch")
                .isEqualTo(3);

        // (b) — la semántica: number / NULL / NULL
        assertThat(precioOrigColumna(urlParseable)).isEqualTo(45000.0);
        assertThat(precioOrigEsNull(urlSinOriginal)).isTrue();
        assertThat(precioOrigEsNull(urlNoParseoEnElScrape)).isTrue();
    }

    private Double precioOrigColumna(String url) throws Exception {
        try (var c = dataSource().getConnection();
             var ps = c.prepareStatement("SELECT precio_orig FROM productos WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                double v = rs.getDouble(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private boolean precioOrigEsNull(String url) throws Exception {
        return precioOrigColumna(url) == null;
    }
}
