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
    @ValueSource(strings = {"pcs/benchmarks/cpu.csv", "pcs/benchmarks/cpu_gaming.csv", "pcs/benchmarks/gpu.csv"})
    void lasTablasRealesCarganSinErrores(String recurso) {
        assertThat(getClass().getClassLoader().getResource(recurso)).isNotNull();
        TablaDeRendimientoCsv.desdeRecurso(recurso);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rtx-2060", "rtx-2070", "rtx-2080-super", "rtx-3050", "rtx-3060", "rtx-3070-ti", "rtx-3080",
            "rtx-4060", "rtx-4070-super", "rtx-5050", "rtx-5060", "rtx-5060-ti", "rtx-5070", "rtx-5070-ti",
            "rtx-5080", "rx-570", "rx-580", "rx-590", "rx-5500-xt", "rx-5600-xt", "rx-5700", "rx-5700-xt",
            "rx-6900-xt", "rx-7600", "rx-9060-xt", "rx-9070", "rx-9070-gre", "rx-9070-xt",
            "arc-a380", "arc-b570", "arc-b580"})
    void laTablaGpuCubreLosModelosDelCatalogo(String clave) {
        assertThat(TablaDeRendimientoCsv.gpu().puntaje(clave)).isPositive();
    }

    @Test
    void laTablaGpuEstaEnEscalaRtx4090Igual1000() {
        assertThat(TablaDeRendimientoCsv.gpu().puntaje("rtx-4090")).isEqualTo(1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rtx-5080>rtx-5070-ti", "rtx-5070-ti>rtx-5070", "rtx-5060-ti>rtx-5060",
            "rx-9070-xt>rx-9070", "rx-9070>rx-9070-gre", "rx-5700-xt>rx-5700", "arc-b580>arc-b570"})
    void elSufijoSeparaModelosQueLaEscaleraEmpata(String par) {
        String[] claves = par.split(">");
        TablaDeRendimiento gpu = TablaDeRendimientoCsv.gpu();
        assertThat(gpu.puntaje(claves[0])).isGreaterThan(gpu.puntaje(claves[1]));
    }

    @Test
    void laTablaGamingEstaEnEscala9850x3dIgual1000() {
        assertThat(TablaDeRendimientoCsv.cpuGaming().puntaje("ryzen-7-9850x3d")).isEqualTo(1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ryzen-7-7800x3d>ryzen-7-7700x", "ryzen-7-9800x3d>ryzen-7-9700x", "ryzen-7-9800x3d>ryzen-9-9900x",
            "ryzen-7-9800x3d>core-i7-14700k", "ryzen-7-5800x3d>ryzen-7-5800x"})
    void enGamingElX3dLeGanaASuGemeloYAlChipDeMasNucleos(String par) {
        String[] claves = par.split(">");
        TablaDeRendimiento gaming = TablaDeRendimientoCsv.cpuGaming();
        assertThat(gaming.puntaje(claves[0])).isGreaterThan(gaming.puntaje(claves[1]));
    }

    @Test
    void lasTablasCompartidasSeCarganUnaSolaVez() {
        assertThat(TablaDeRendimientoCsv.cpuGaming()).isSameAs(TablaDeRendimientoCsv.cpuGaming());
        assertThat(TablaDeRendimientoCsv.cpu()).isSameAs(TablaDeRendimientoCsv.cpu());
        assertThat(TablaDeRendimientoCsv.gpu()).isSameAs(TablaDeRendimientoCsv.gpu());
    }
}
