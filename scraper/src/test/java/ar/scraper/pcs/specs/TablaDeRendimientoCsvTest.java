package ar.scraper.pcs.specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TablaDeRendimientoCsv — benchmark table loaded from the classpath")
class TablaDeRendimientoCsvTest {

    private static TablaDeRendimiento leer(String csv) {
        return TablaDeRendimientoCsv.leer(new BufferedReader(new StringReader(csv)), "test.csv");
    }

    @Test
    void devuelveElPuntajeDeUnaClaveConocida() {
        TablaDeRendimiento tabla = leer("clave,puntaje,muestras\nrtx-5080-ti,912,0\nrtx-5080,840,0\n");
        assertThat(tabla.puntaje("rtx-5080-ti")).isEqualTo(912);
        assertThat(tabla.puntaje("rtx-5080")).isEqualTo(840);
    }

    @Test
    void abstieneConCeroAnteClaveDesconocidaVaciaONula() {
        TablaDeRendimiento tabla = leer("clave,puntaje,muestras\nrx-9070-xt,700,0\n");
        assertThat(tabla.puntaje("rx-9070")).isZero();
        assertThat(tabla.puntaje("")).isZero();
        assertThat(tabla.puntaje(null)).isZero();
    }

    @Test
    void ignoraLineasEnBlanco() {
        assertThat(leer("clave,puntaje,muestras\n\nryzen-9-9950x3d,6400,12\n\n").puntaje("ryzen-9-9950x3d")).isEqualTo(6400);
    }

    @Test
    void recursoAusenteEsUnaTablaVaciaQueAbstiene() {
        assertThat(TablaDeRendimientoCsv.desdeRecurso("pcs/benchmarks/no-existe.csv").puntaje("rtx-5080")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "clave,puntaje\nrtx-5080,840\n",
            "clave,puntaje,muestras\nrtx-5080,840\n",
            "clave,puntaje,muestras\nrtx-5080,ochocientos,0\n",
            "clave,puntaje,muestras\nrtx-5080,0,0\n",
            "clave,puntaje,muestras\nrtx-5080,840,-1\n",
            "clave,puntaje,muestras\nRTX 5080,840,0\n",
            "clave,puntaje,muestras\nrtx-5080,840,0\nrtx-5080,850,0\n"})
    void fallaRapidoAnteUnArchivoMalformado(String csv) {
        assertThatThrownBy(() -> leer(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("test.csv");
    }

    @Test
    void elErrorNombraLaLinea() {
        assertThatThrownBy(() -> leer("clave,puntaje,muestras\nrtx-5080,840,0\nrtx-5080,850,0\n"))
                .hasMessageStartingWith("test.csv:3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pcs/benchmarks/cpu.csv", "pcs/benchmarks/gpu.csv"})
    void lasTablasRealesCarganSinErrores(String recurso) {
        assertThat(getClass().getClassLoader().getResource(recurso)).isNotNull();
        TablaDeRendimientoCsv.desdeRecurso(recurso);
    }

    @Test
    void lasTablasCompartidasSeCarganUnaSolaVez() {
        assertThat(TablaDeRendimientoCsv.cpu()).isSameAs(TablaDeRendimientoCsv.cpu());
        assertThat(TablaDeRendimientoCsv.gpu()).isSameAs(TablaDeRendimientoCsv.gpu());
    }
}
