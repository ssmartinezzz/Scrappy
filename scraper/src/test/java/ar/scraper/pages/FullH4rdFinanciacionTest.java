package ar.scraper.pages;

import ar.scraper.pages.FullH4rdFinanciacion.Detalle;
import ar.scraper.pages.FullH4rdFinanciacion.Plan;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Scraping")
@Feature("Fullh4rd financing")
@DisplayName("FullH4rdFinanciacion — product detail page parser")
class FullH4rdFinanciacionTest {

    private static Detalle real;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (var in = FullH4rdFinanciacionTest.class.getResourceAsStream("/fixtures/fullh4rd/producto-detalle.html")) {
            real = FullH4rdFinanciacion.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String pagina(String contado, String cuotas) {
        return """
                <html><body>
                <p><span>Precio especial:</span><span class="bold">%s</span></p>
                <button popovertarget="mercadopago-cuotas-modal"><img alt="Mercado Pago"></button>
                <dialog id="mercadopago-cuotas-modal"><header><h3>$1.050</h3></header>
                <table><tbody>%s</tbody></table></dialog>
                </body></html>
                """.formatted(contado, cuotas);
    }

    @Test
    @DisplayName("Lee precio especial, precio de lista y los dos banners de stock")
    void readsPricesAndStock() {
        assertThat(real.precioContado()).isEqualTo(2_126_050.0);
        assertThat(real.precioLista()).isEqualTo(2_232_353.0);
        assertThat(real.stockWeb()).isEqualTo("Stock alto en la web");
        assertThat(real.stockLocal()).isEqualTo("Sin stock en el local");
    }

    @Test
    @DisplayName("Lee las 7 cuotas exactas; el ≈ aparece desde la de 6")
    void readsAllSevenPlans() {
        assertThat(real.planes()).extracting(Plan::cuotas).containsExactly(1, 3, 6, 9, 12, 18, 24);
        assertThat(real.planes()).extracting(Plan::montoCuota).containsExactly(
                2_232_353.0, 807_899.0, 428_754.0, 307_096.0, 242_724.0, 183_077.0, 150_595.0);
        assertThat(real.planes()).extracting(Plan::aproximado)
                .containsExactly(false, false, true, true, true, true, true);
        assertThat(real.planes()).extracting(Plan::medio).containsOnly("Mercado Pago");
        assertThat(real.planes().get(1).total()).isEqualTo(3 * 807_899.0);
    }

    @Test
    @DisplayName("Ninguna cuota de la pagina real es sin interes: 1 cuota ya es +5% sobre el contado")
    void noRealPlanIsInterestFree() {
        assertThat(real.planes()).extracting(Plan::sinInteres).containsOnly(false);
    }

    @Test
    @DisplayName("Sin interes si el total no pasa el contado, con holgura de medio peso por cuota")
    void interestFreeWhenTotalDoesNotExceedCash() {
        Detalle d = FullH4rdFinanciacion.parse(pagina("$1.001", """
                <tr><td>1 cuota</td><td>$1.000</td></tr>
                <tr><td>3 cuotas</td><td>≈ $334</td></tr>
                <tr><td>3 cuotas</td><td>≈ $335</td></tr>
                <tr><td>6 cuotas</td><td>≈ $200</td></tr>"""));

        assertThat(d.planes()).extracting(Plan::sinInteres).containsExactly(true, true, false, false);
    }

    @Test
    @DisplayName("Sin bloque de financiacion: planes vacios, precio y sin medio")
    void pageWithoutFinancingBlock() {
        Detalle d = FullH4rdFinanciacion.parse("""
                <html><body><p><span>Precio especial:</span><span class="bold">$500</span></p></body></html>""");

        assertThat(d.planes()).isEmpty();
        assertThat(d.precioContado()).isEqualTo(500.0);
        assertThat(d.precioLista()).isNull();
        assertThat(d.stockWeb()).isNull();
    }

    @Test
    @DisplayName("HTML vacio o nulo no lanza")
    void emptyHtmlDoesNotThrow() {
        assertThat(FullH4rdFinanciacion.parse("").planes()).isEmpty();
        assertThat(FullH4rdFinanciacion.parse(null).precioContado()).isNull();
    }
}
