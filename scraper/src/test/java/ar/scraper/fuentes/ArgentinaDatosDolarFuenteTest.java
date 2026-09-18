package ar.scraper.fuentes;

import ar.scraper.indices.Indice;
import ar.scraper.indices.PuntoIndice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture trimmed from a live {@code GET
 * https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial} response
 * (verified 2026-09-18): {@code [{casa, compra, venta, fecha}]}. Uses
 * {@code venta} — the selling rate is what a peso-priced product converts
 * against — and {@code venta} is already a level, no conversion needed.
 */
class ArgentinaDatosDolarFuenteTest {

    private static final String FIXTURE = """
            [
              {"casa": "oficial", "compra": 1480, "venta": 1530, "fecha": "2026-09-14"},
              {"casa": "oficial", "compra": 1480, "venta": 1530, "fecha": "2026-09-16"},
              {"casa": "oficial", "compra": 1485, "venta": 1535, "fecha": "2026-09-17"}
            ]
            """;

    @Test
    void leeVentaComoElNivelYFechaComoLocalDate() throws Exception {
        List<PuntoIndice> puntos = ArgentinaDatosDolarFuente.parsear(FIXTURE);

        assertThat(puntos).hasSize(3);
        assertThat(puntos).extracting(PuntoIndice::indice).containsOnly(Indice.USD_OFICIAL);
        assertThat(puntos).extracting(PuntoIndice::valor).containsExactly(1530.0, 1530.0, 1535.0);
        assertThat(puntos).extracting(PuntoIndice::fecha)
                .containsExactly(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-16"),
                        LocalDate.parse("2026-09-17"));
    }

    @Test
    void unCuerpoQueNoEsUnArrayEsUnaFalla() {
        assertThatThrownBy(() -> ArgentinaDatosDolarFuente.parsear("{}"))
                .isInstanceOf(ar.scraper.indices.FuenteIndiceException.class);
    }
}
