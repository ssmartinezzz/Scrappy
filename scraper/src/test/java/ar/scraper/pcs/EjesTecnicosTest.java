package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EjesTecnicos} — one named comparator per slot's non-price axis.
 * {@link CriterioPorEjesTecnicos} always appends precio/url after these;
 * these tests only pin the axis order and the abstention-last rule.
 */
@DisplayName("EjesTecnicos — technical-quality axes, abstention always ranks last")
class EjesTecnicosTest {

    private static TechSpecs conGama(Gama gama) {
        return new TechSpecs("", "", "", 0, 0, "", gama, Certificacion.NINGUNA);
    }

    private static TechSpecs conCertificacion(Certificacion cert) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, cert);
    }

    private static TechSpecs conTipoAlmacenamiento(TipoAlmacenamiento tipo, int gb) {
        return new TechSpecs("", "", "", 0, gb, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0, tipo);
    }

    private static TechSpecs conRam(String ddr, int mhz, int gb) {
        return new TechSpecs("", ddr, "", 0, gb, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, mhz, TipoAlmacenamiento.DESCONOCIDO);
    }

    private static TechSpecs conMother(String socket, String ddr) {
        return new TechSpecs(socket, ddr, "", 0, 0, "");
    }

    // ── CPU / GPU: gama desc, DESCONOCIDA siempre última ─────────────────

    @Test
    void cpuPrefiereAltaSobreMedia() {
        assertThat(EjesTecnicos.CPU.compare(conGama(Gama.ALTA), conGama(Gama.MEDIA))).isNegative();
    }

    @Test
    void cpuPrefiereMediaSobreBaja() {
        assertThat(EjesTecnicos.CPU.compare(conGama(Gama.MEDIA), conGama(Gama.BAJA))).isNegative();
    }

    @Test
    void cpuLaGamaDesconocidaSiempreVaUltima() {
        assertThat(EjesTecnicos.CPU.compare(conGama(Gama.BAJA), conGama(Gama.DESCONOCIDA))).isNegative();
        assertThat(EjesTecnicos.CPU.compare(conGama(Gama.DESCONOCIDA), conGama(Gama.ALTA))).isPositive();
    }

    @Test
    void gpuUsaElMismoOrdenQueCpu() {
        assertThat(EjesTecnicos.GPU.compare(conGama(Gama.ALTA), conGama(Gama.DESCONOCIDA))).isNegative();
    }

    // ── Fuente: certificación desc ───────────────────────────────────────

    @Test
    void fuentePrefiereGoldSobreBronze() {
        assertThat(EjesTecnicos.FUENTE.compare(conCertificacion(Certificacion.GOLD), conCertificacion(Certificacion.BRONZE)))
                .isNegative();
    }

    @Test
    void fuenteSinCertificacionVaUltima() {
        assertThat(EjesTecnicos.FUENTE.compare(conCertificacion(Certificacion.WHITE), conCertificacion(Certificacion.NINGUNA)))
                .isNegative();
    }

    // ── Gabinete: sin ejes, siempre empata (sólo precio decide) ──────────

    @Test
    void gabineteSiempreEmpata() {
        TechSpecs a = new TechSpecs("", "", "ATX", 0, 0, "");
        TechSpecs b = new TechSpecs("", "", "ITX", 0, 0, "");

        assertThat(EjesTecnicos.GABINETE.compare(a, b)).isZero();
    }

    // ── Almacenamiento: tipo desc (NVME > SSD > HDD > DESCONOCIDO) → GB desc ─

    @Test
    void almacenamientoPrefiereNvmeSobreSsd() {
        assertThat(EjesTecnicos.ALMACENAMIENTO.compare(
                conTipoAlmacenamiento(TipoAlmacenamiento.NVME, 500),
                conTipoAlmacenamiento(TipoAlmacenamiento.SSD, 500))).isNegative();
    }

    @Test
    void almacenamientoPrefiereSsdSobreHdd() {
        assertThat(EjesTecnicos.ALMACENAMIENTO.compare(
                conTipoAlmacenamiento(TipoAlmacenamiento.SSD, 500),
                conTipoAlmacenamiento(TipoAlmacenamiento.HDD, 500))).isNegative();
    }

    @Test
    void almacenamientoDesconocidoSiempreVaUltimoAunConMasCapacidad() {
        assertThat(EjesTecnicos.ALMACENAMIENTO.compare(
                conTipoAlmacenamiento(TipoAlmacenamiento.HDD, 100),
                conTipoAlmacenamiento(TipoAlmacenamiento.DESCONOCIDO, 4096))).isNegative();
    }

    @Test
    void almacenamientoDesempataPorGbDescDentroDelMismoTipo() {
        assertThat(EjesTecnicos.ALMACENAMIENTO.compare(
                conTipoAlmacenamiento(TipoAlmacenamiento.SSD, 1000),
                conTipoAlmacenamiento(TipoAlmacenamiento.SSD, 500))).isNegative();
    }

    // ── RAM: DDR desc → MHz desc → GB desc ───────────────────────────────

    @Test
    void ramPrefiereDdr5SobreDdr4() {
        assertThat(EjesTecnicos.RAM.compare(conRam("DDR5", 0, 16), conRam("DDR4", 0, 16))).isNegative();
    }

    @Test
    void ramDdrDesconocidaVaUltimaAunConMasGb() {
        assertThat(EjesTecnicos.RAM.compare(conRam("DDR4", 0, 16), conRam("", 0, 64))).isNegative();
    }

    @Test
    void ramEmpataDdrDesempataPorMhz() {
        assertThat(EjesTecnicos.RAM.compare(conRam("DDR4", 6000, 16), conRam("DDR4", 3200, 16))).isNegative();
    }

    @Test
    void ramSinMhzVaUltimaEnEseEjeAunqueLaOtraTengaMenosGb() {
        assertThat(EjesTecnicos.RAM.compare(conRam("DDR4", 3200, 8), conRam("DDR4", 0, 64))).isNegative();
    }

    @Test
    void ramEmpataDdrYMhzDesempataPorGb() {
        assertThat(EjesTecnicos.RAM.compare(conRam("DDR4", 3200, 32), conRam("DDR4", 3200, 16))).isNegative();
    }

    // ── Motherboard: DDR desc, derivada del socket cuando no la declara ──

    @Test
    void motherUsaLaDdrDeclarada() {
        assertThat(EjesTecnicos.MOTHER.compare(conMother("", "DDR5"), conMother("", "DDR4"))).isNegative();
    }

    @Test
    void motherDerivaLaDdrDelSocketCuandoNoLaDeclara() {
        // AM5 -> DDR5 derivada, AM4 -> DDR4 derivada (misma tabla que ContextoDeArmado).
        assertThat(EjesTecnicos.MOTHER.compare(conMother("AM5", ""), conMother("AM4", ""))).isNegative();
    }

    @Test
    void motherConDdrIndeterminadaVaUltima() {
        // LGA1700 es plataforma mixta (phase-1): no deriva a ninguna DDR, y
        // ese "" tiene que perder incluso contra un socket con DDR derivada.
        assertThat(EjesTecnicos.MOTHER.compare(conMother("AM4", ""), conMother("LGA1700", ""))).isNegative();
    }
}
