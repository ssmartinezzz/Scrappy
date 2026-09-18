package ar.scraper.fuentes;

import ar.scraper.indices.FuenteIndiceException;
import ar.scraper.indices.Indice;
import ar.scraper.indices.PuntoIndice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Documented shape ({@code {"data": [[fecha, valor], ...]}}), unlike
 * {@link ArgentinaDatosIpcFuente}: here {@code valor} already IS an index
 * level, so no conversion is needed. The live series id this fallback used
 * ({@code 148.3_INIVELGENERAL_DICI_M_26}) currently returns
 * {@code {"errors": [...], "failed_series": [...]}} (verified 2026-09-18,
 * reachable but stale) — the second test below is exactly that shape, and it
 * must fail closed rather than throw an unrelated NPE.
 */
class DatosGobIpcFuenteTest {

    @Test
    void leeElNivelDirectoDeLaTuplaFechaValor() throws Exception {
        String fixture = """
                {"data": [["2026-01-01", 500.2], ["2026-02-01", 512.7]]}
                """;

        List<PuntoIndice> puntos = DatosGobIpcFuente.parsear(fixture);

        assertThat(puntos).extracting(PuntoIndice::indice).containsOnly(Indice.IPC);
        assertThat(puntos).extracting(PuntoIndice::fecha)
                .containsExactly(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"));
        assertThat(puntos).extracting(PuntoIndice::valor).containsExactly(500.2, 512.7);
    }

    @Test
    void unaSerieInexistenteFallaCerradoNoConNpe() {
        String fixture = """
                {"errors": [{"error": "Serie inexistente: 148.3_INIVELGENERAL_DICI_M_26"}],
                 "failed_series": ["148.3_INIVELGENERAL_DICI_M_26"]}
                """;

        assertThatThrownBy(() -> DatosGobIpcFuente.parsear(fixture))
                .isInstanceOf(FuenteIndiceException.class);
    }
}
