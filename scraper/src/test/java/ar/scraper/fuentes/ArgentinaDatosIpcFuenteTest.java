package ar.scraper.fuentes;

import ar.scraper.indices.Indice;
import ar.scraper.indices.PuntoIndice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Fixture trimmed from a live {@code GET
 * https://api.argentinadatos.com/v1/finanzas/indices/inflacion} response
 * (verified 2026-09-18). Each {@code valor} there is the MONTHLY INFLATION
 * RATE PERCENTAGE, not an index level (it can be negative, and the live
 * series starts in 1943 well below 100) — the old {@code InflacionService}
 * misread this same source as a level. This parser must integrate the rates
 * into a synthetic cumulative level, never store a rate directly:
 * {@code indice_valor.valor} has {@code CHECK (valor > 0)}.
 */
class ArgentinaDatosIpcFuenteTest {

    private static final String FIXTURE = """
            [
              {"fecha": "2026-03-31", "valor": 3.4},
              {"fecha": "2026-04-30", "valor": 2.6},
              {"fecha": "2026-05-31", "valor": 2.1},
              {"fecha": "2026-06-30", "valor": 1.9}
            ]
            """;

    @Test
    void integraLasTasasMensualesEnUnNivelAcumuladoCreciente() throws Exception {
        List<PuntoIndice> puntos = ArgentinaDatosIpcFuente.parsear(FIXTURE);

        assertThat(puntos).hasSize(4);
        assertThat(puntos).allMatch(p -> p.indice() == Indice.IPC);
        assertThat(puntos).allMatch(p -> p.valor() > 0);
        assertThat(puntos).extracting(PuntoIndice::fecha)
                .containsExactly(
                        LocalDate.parse("2026-03-31"), LocalDate.parse("2026-04-30"),
                        LocalDate.parse("2026-05-31"), LocalDate.parse("2026-06-30"));

        // ratio between consecutive levels must equal (1 + monthly rate/100)
        double ratio1a2 = puntos.get(1).valor() / puntos.get(0).valor();
        assertThat(ratio1a2).isCloseTo(1.026, within(0.0001));
        double ratio3a4 = puntos.get(3).valor() / puntos.get(2).valor();
        assertThat(ratio3a4).isCloseTo(1.019, within(0.0001));
    }

    @Test
    void anclaEnDiciembre2016Igual100YDescartaLoAnterior() throws Exception {
        String desde1943 = """
            [
              {"fecha": "1943-01-31", "valor": 3.0},
              {"fecha": "1989-07-31", "valor": 196.6},
              {"fecha": "2016-12-31", "valor": 1.2},
              {"fecha": "2017-01-31", "valor": 1.6},
              {"fecha": "2017-02-28", "valor": 2.5}
            ]
            """;

        List<PuntoIndice> puntos = ArgentinaDatosIpcFuente.parsear(desde1943);

        assertThat(puntos).extracting(PuntoIndice::fecha)
                .containsExactly(LocalDate.parse("2016-12-31"), LocalDate.parse("2017-01-31"), LocalDate.parse("2017-02-28"));
        assertThat(puntos.get(0).valor()).isEqualTo(100.0);
        assertThat(puntos.get(1).valor()).isCloseTo(101.6, within(0.0001));
        assertThat(puntos.get(2).valor()).isCloseTo(101.6 * 1.025, within(0.0001));
    }

    @Test
    void unaTasaNegativaDaUnaCaidaDeNivelSinCruzarCero() throws Exception {
        List<PuntoIndice> puntos = ArgentinaDatosIpcFuente.parsear("""
                [{"fecha": "2020-06-30", "valor": 0.1}, {"fecha": "2020-07-31", "valor": -5.6}]
                """);

        assertThat(puntos.get(1).valor()).isLessThan(puntos.get(0).valor());
        assertThat(puntos.get(1).valor()).isPositive();
    }

    @Test
    void unCuerpoQueNoEsUnArrayEsUnaFalla() {
        assertThatThrownBy(() -> ArgentinaDatosIpcFuente.parsear("{\"errors\": [\"nope\"]}"))
                .isInstanceOf(ar.scraper.indices.FuenteIndiceException.class);
    }
}
