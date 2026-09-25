package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EjesTecnicos#COOLER} — profundidad del eje AIRE (T16,
 * pc-builder-homelab, D2). Hasta acá los 85 AIRE del catálogo tenían
 * {@code radiadorMm=0} (ese eje es exclusivo de líquidos) y empataban en
 * TODO, así que el más barato ganaba siempre — pedido del usuario: "no
 * importa qué gama, en los cooler siempre estaba ganando uno medio
 * pedorro". Clase de disipador y heatpipes son el segundo/tercer key, DESPUÉS
 * de tipo/radiador — el eje líquido queda intacto.
 */
@DisplayName("EjesTecnicos.COOLER — clase de disipador y heatpipes (T16)")
class EjesTecnicosT16Test {

    private static TechSpecs cooler(TipoCooler tipo, int radiadorMm, ClaseDisipador clase, int heatpipes) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false, tipo, 0,
                TamanioGabinete.DESCONOCIDO, radiadorMm, clase, heatpipes);
    }

    @Test
    @DisplayName("entre dos AIRE, la doble torre gana a la torre simple")
    void entreDosAireGanaLaDobleTorre() {
        assertThat(EjesTecnicos.COOLER.compare(
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.DOBLE_TORRE, 0),
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.TORRE, 0)))
                .isNegative();
    }

    @Test
    @DisplayName("D13: una clase desconocida va última, nunca gana a una torre simple")
    void claseDesconocidaVaUltima() {
        assertThat(EjesTecnicos.COOLER.compare(
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.TORRE, 0),
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.DESCONOCIDA, 0)))
                .isNegative();
    }

    @Test
    @DisplayName("a igual clase, ganan más heatpipes")
    void aIgualClaseGananMasHeatpipes() {
        assertThat(EjesTecnicos.COOLER.compare(
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.TORRE, 6),
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.TORRE, 4)))
                .isNegative();
    }

    @Test
    @DisplayName("D13: 0 heatpipes (abstención) va última, nunca gana a un valor declarado")
    void ceroHeatpipesVaUltimo() {
        assertThat(EjesTecnicos.COOLER.compare(
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.TORRE, 4),
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.TORRE, 0)))
                .isNegative();
    }

    @Test
    @DisplayName("el ejemplo medido: el cooler barato sin clase ya no gana contra uno con clase real")
    void elCoolerBaratoSinSenalYaNoGanaPorDefecto() {
        // "CPU Cooler Raptor Cryo RGB - 3P" ($18.400) ganaba las 16
        // combinaciones medidas (T8c) porque las dos claves anteriores
        // (tipo, radiador) empataban entre AIRE. Acá simulan ese producto
        // (sin clase ni heatpipes) contra un Hyper 212 real (TORRE, 4
        // heatpipes) — el barato ya no puede ganar por ranking.
        assertThat(EjesTecnicos.COOLER.compare(
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.DESCONOCIDA, 0),
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.TORRE, 4)))
                .isPositive();
    }

    @Test
    @DisplayName("el tipo sigue mandando por encima de la clase: un líquido sin clase le gana a un aire doble torre")
    void elTipoSigueMandandoPorEncimaDeLaClase() {
        assertThat(EjesTecnicos.COOLER.compare(
                cooler(TipoCooler.LIQUIDO, 0, ClaseDisipador.DESCONOCIDA, 0),
                cooler(TipoCooler.AIRE, 0, ClaseDisipador.DOBLE_TORRE, 6)))
                .isNegative();
    }

    @Test
    @DisplayName("el eje líquido (tipo → radiador) queda intacto: la clase no participa entre dos líquidos")
    void elEjeLiquidoQuedaIntacto() {
        assertThat(EjesTecnicos.COOLER.compare(
                cooler(TipoCooler.LIQUIDO, 360, ClaseDisipador.DESCONOCIDA, 0),
                cooler(TipoCooler.LIQUIDO, 240, ClaseDisipador.DOBLE_TORRE, 6)))
                .isNegative();
    }
}
