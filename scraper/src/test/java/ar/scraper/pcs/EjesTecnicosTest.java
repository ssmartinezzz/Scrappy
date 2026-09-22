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

    private static TechSpecs conRamModulos(String ddr, int modulos, int mhz, int gb) {
        return new TechSpecs("", ddr, "", 0, gb, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, mhz,
                TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", 0, 0, modulos, false);
    }

    private static TechSpecs conMother(String socket, String ddr) {
        return new TechSpecs(socket, ddr, "", 0, 0, "");
    }

    private static TechSpecs conMotherTier(String ddr, int tierChipset) {
        return new TechSpecs("", ddr, "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", 0, tierChipset, 0, false);
    }

    private static TechSpecs conCpuGeneracion(Gama gama, int generacion) {
        return new TechSpecs("", "", "", 0, 0, "", gama, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", generacion, 0, 0, false);
    }

    private static TechSpecs conGpu(Gama gama, int generacion, int vramGb) {
        return new TechSpecs("", "", "", 0, vramGb, "", gama, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", generacion, 0, 0, false);
    }

    private static TechSpecs conCpuNivel(Gama gama, int nivel, int generacion) {
        return new TechSpecs("", "", "", 0, 0, "", gama, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", generacion, 0, 0, false,
                TipoCooler.DESCONOCIDO, nivel);
    }

    private static TechSpecs conGpuNivel(Gama gama, int nivel, int generacion, int vramGb) {
        return new TechSpecs("", "", "", 0, vramGb, "", gama, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", generacion, 0, 0, false,
                TipoCooler.DESCONOCIDO, nivel);
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

    // ── CPU: gama → generación desc, abstención (0) última (D4) ─────────

    @Test
    void cpuEmpataGamaDesempataPorGeneracionDesc() {
        assertThat(EjesTecnicos.CPU.compare(conCpuGeneracion(Gama.ALTA, 14), conCpuGeneracion(Gama.ALTA, 12)))
                .isNegative();
    }

    @Test
    void cpuSinGeneracionVaUltimaEnEseEjeAunqueLaGamaEmpate() {
        assertThat(EjesTecnicos.CPU.compare(conCpuGeneracion(Gama.ALTA, 12), conCpuGeneracion(Gama.ALTA, 0)))
                .isNegative();
    }

    @Test
    void cpuLaGamaSigueGanandoleALaGeneracion() {
        // Una ALTA de generación vieja le gana a una MEDIA de generación nueva.
        assertThat(EjesTecnicos.CPU.compare(conCpuGeneracion(Gama.ALTA, 9), conCpuGeneracion(Gama.MEDIA, 14)))
                .isNegative();
    }

    // ── GPU: gama → generación desc → VRAM desc (D4) ─────────────────────

    @Test
    void gpuEmpataGamaDesempataPorGeneracionDesc() {
        assertThat(EjesTecnicos.GPU.compare(conGpu(Gama.ALTA, 5, 16), conGpu(Gama.ALTA, 4, 16))).isNegative();
    }

    @Test
    void gpuEmpataGamaYGeneracionDesempataPorVramDesc() {
        assertThat(EjesTecnicos.GPU.compare(conGpu(Gama.ALTA, 5, 16), conGpu(Gama.ALTA, 5, 8))).isNegative();
    }

    @Test
    void gpuSinGeneracionVaUltimaEnEseEjeAunqueLaGamaEmpate() {
        assertThat(EjesTecnicos.GPU.compare(conGpu(Gama.ALTA, 5, 8), conGpu(Gama.ALTA, 0, 24))).isNegative();
    }

    // ── nivel: el escalón DENTRO de la gama, comparable entre marcas (D1) ─

    @Test
    void cpuEmpataGamaDesempataPorNivelDesc() {
        // i9/Ryzen 9 (nivel 9) le gana a i7/Ryzen 7 (nivel 7) dentro de ALTA.
        assertThat(EjesTecnicos.CPU.compare(conCpuNivel(Gama.ALTA, 9, 9), conCpuNivel(Gama.ALTA, 7, 14)))
                .isNegative();
    }

    @Test
    void cpuElNivelLeGanaALaGeneracion() {
        // El caso del usuario: un Ryzen 9 de la serie 9000 le gana a un i7 de
        // 14ª. Antes de D1 ganaba el i7 porque 14 > 9, comparando magnitudes
        // de marcas distintas.
        assertThat(EjesTecnicos.CPU.compare(conCpuNivel(Gama.ALTA, 9, 9), conCpuNivel(Gama.ALTA, 7, 14)))
                .isNegative();
    }

    @Test
    void cpuSinNivelVaUltimoEnEseEjeAunqueLaGamaEmpate() {
        assertThat(EjesTecnicos.CPU.compare(conCpuNivel(Gama.ALTA, 3, 0), conCpuNivel(Gama.ALTA, 0, 14)))
                .isNegative();
    }

    @Test
    void cpuLaGamaSigueGanandoleAlNivel() {
        assertThat(EjesTecnicos.CPU.compare(conCpuNivel(Gama.ALTA, 3, 0), conCpuNivel(Gama.MEDIA, 9, 0)))
                .isNegative();
    }

    @Test
    void gpuEmpataGamaDesempataPorNivelDesc() {
        // RTX 5080 (nivel 80) le gana a RX 9070 (nivel 70): las dos son ALTA.
        assertThat(EjesTecnicos.GPU.compare(conGpuNivel(Gama.ALTA, 80, 5, 16), conGpuNivel(Gama.ALTA, 70, 9, 16)))
                .isNegative();
    }

    @Test
    void gpuSinNivelVaUltimoEnEseEjeAunqueLaGamaEmpate() {
        assertThat(EjesTecnicos.GPU.compare(conGpuNivel(Gama.ALTA, 50, 3, 8), conGpuNivel(Gama.ALTA, 0, 5, 24)))
                .isNegative();
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

    // ── Cooler: LIQUIDO desc sobre AIRE, DESCONOCIDO siempre última (T4d-2) ─

    private static TechSpecs conTipoCooler(TipoCooler tipo) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", 0, 0, 0, false, tipo);
    }

    @Test
    void coolerPrefiereLiquidoSobreAire() {
        assertThat(EjesTecnicos.COOLER.compare(conTipoCooler(TipoCooler.LIQUIDO), conTipoCooler(TipoCooler.AIRE)))
                .isNegative();
    }

    @Test
    void coolerDesconocidoSiempreVaUltimo() {
        assertThat(EjesTecnicos.COOLER.compare(conTipoCooler(TipoCooler.AIRE), conTipoCooler(TipoCooler.DESCONOCIDO)))
                .isNegative();
        assertThat(EjesTecnicos.COOLER.compare(conTipoCooler(TipoCooler.DESCONOCIDO), conTipoCooler(TipoCooler.LIQUIDO)))
                .isPositive();
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

    // ── RAM: módulos (kit) va ANTES que MHz — D4 ──────────────────────────

    @Test
    void ramPrefiereMasModulosSobreMenosAunqueLaOtraTengaMasMhz() {
        // Un kit dual-channel le gana a un stick único más rápido.
        assertThat(EjesTecnicos.RAM.compare(
                conRamModulos("DDR5", 2, 3200, 16),
                conRamModulos("DDR5", 1, 6000, 16))).isNegative();
    }

    @Test
    void ramSinModulosVaUltimaEnEseEjeAunqueLaOtraTengaMenosMhz() {
        assertThat(EjesTecnicos.RAM.compare(
                conRamModulos("DDR5", 2, 3200, 16),
                conRamModulos("DDR5", 0, 6000, 16))).isNegative();
    }

    @Test
    void ramEmpataModulosDesempataPorMhz() {
        assertThat(EjesTecnicos.RAM.compare(
                conRamModulos("DDR5", 2, 6000, 16),
                conRamModulos("DDR5", 2, 3200, 16))).isNegative();
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

    // ── Motherboard: DDR → tier de chipset (X/Z=1 < B=2 < A/H=3, 0 última) — D4 ─

    @Test
    void motherEmpataDdrDesempataPorTierChipset() {
        assertThat(EjesTecnicos.MOTHER.compare(conMotherTier("DDR5", 1), conMotherTier("DDR5", 2))).isNegative();
    }

    @Test
    void motherTierBLeGanaATierAyH() {
        assertThat(EjesTecnicos.MOTHER.compare(conMotherTier("DDR5", 2), conMotherTier("DDR5", 3))).isNegative();
    }

    @Test
    void motherSinChipsetLegibleVaUltimaEnEseEjeAunqueLaDdrEmpate() {
        assertThat(EjesTecnicos.MOTHER.compare(conMotherTier("DDR5", 1), conMotherTier("DDR5", 0))).isNegative();
    }

    @Test
    void motherLaDdrSigueGanandoleAlTierDeChipset() {
        // Una DDR5 de tier bajo le gana a una DDR4 de tier alto.
        assertThat(EjesTecnicos.MOTHER.compare(conMotherTier("DDR5", 3), conMotherTier("DDR4", 1))).isNegative();
    }

    // ── Motherboard: tier de chipset RELATIVO a la gama pedida — D9 ──────

    @Test
    void motherConGamaMediaPrefiereBSobreXZ() {
        // MEDIA -> target=2 (B); B (tier=2, distancia 0) esta mas cerca que X/Z (tier=1, distancia 1).
        assertThat(EjesTecnicos.mother(Gama.MEDIA).compare(conMotherTier("DDR5", 2), conMotherTier("DDR5", 1)))
                .isNegative();
    }

    @Test
    void motherConGamaMediaPrefiereBSobreAyH() {
        // B (tier=2, distancia 0) esta mas cerca del target MEDIA=2 que A/H (tier=3, distancia 1).
        assertThat(EjesTecnicos.mother(Gama.MEDIA).compare(conMotherTier("DDR5", 2), conMotherTier("DDR5", 3)))
                .isNegative();
    }

    @Test
    void motherConGamaBajaPrefiereAyHSobreB() {
        // BAJA -> target=3 (A/H); A/H (tier=3, distancia 0) le gana a B (tier=2, distancia 1).
        assertThat(EjesTecnicos.mother(Gama.BAJA).compare(conMotherTier("DDR5", 3), conMotherTier("DDR5", 2)))
                .isNegative();
    }

    @Test
    void motherSinGamaPedidaMantieneElOrdenAbsolutoDeT3() {
        // gamaPedida == null: sin target, vuelve al orden absoluto de T3 (X/Z < B < A/H).
        assertThat(EjesTecnicos.mother(null).compare(conMotherTier("DDR5", 1), conMotherTier("DDR5", 2)))
                .isNegative();
    }

    @Test
    void motherAbstencionSigueUltimaConGamaPedida() {
        assertThat(EjesTecnicos.mother(Gama.MEDIA).compare(conMotherTier("DDR5", 2), conMotherTier("DDR5", 0)))
                .isNegative();
    }

    @Test
    void motherConGamaMediaLaDdrSigueGanandoleAlTierDeChipset() {
        // Una DDR5 lejos del target le gana a una DDR4 en el target exacto.
        assertThat(EjesTecnicos.mother(Gama.MEDIA).compare(conMotherTier("DDR5", 1), conMotherTier("DDR4", 2)))
                .isNegative();
    }
}
