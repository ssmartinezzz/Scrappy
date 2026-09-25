package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EjesTecnicos}'s three homelab additions (pc-builder-homelab T3/T4):
 * {@code RAM_HOMELAB} (capacity first, a VM host wants GB over speed),
 * {@code ALMACENAMIENTO_DATOS} (capacity first, HDD preferred — the bulk
 * data disk, not the boot disk) and {@code MINI_PC} (the CPU scale reused
 * for a barebone's own capacidadGb instead of año/generación).
 */
@DisplayName("EjesTecnicos — homelab axes (RAM_HOMELAB, ALMACENAMIENTO_DATOS, MINI_PC)")
class EjesTecnicosHomelabTest {

    private static TechSpecs conRam(String ddr, int mhz, int gb) {
        return new TechSpecs("", ddr, "", 0, gb, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, mhz,
                TipoAlmacenamiento.DESCONOCIDO);
    }

    private static TechSpecs conTipoAlmacenamiento(TipoAlmacenamiento tipo, int gb) {
        return new TechSpecs("", "", "", 0, gb, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0, tipo);
    }

    private static TechSpecs conMiniPc(Gama gama, int nivel, int gb) {
        return new TechSpecs("", "", "", 0, gb, "", gama, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false, TipoCooler.DESCONOCIDO, nivel);
    }

    // ── RAM_HOMELAB: capacidad manda, antes que DDR ─────────────────────

    @Test
    @DisplayName("más capacidad gana, aunque sea DDR4 contra DDR5")
    void masCapacidadGanaAunqueSeaDdrMasViejo() {
        TechSpecs pocaYRapida = conRam("DDR5", 6000, 16);
        TechSpecs muchaYMasVieja = conRam("DDR4", 3200, 64);
        assertThat(EjesTecnicos.RAM_HOMELAB.compare(muchaYMasVieja, pocaYRapida)).isLessThan(0);
    }

    @Test
    @DisplayName("a igual capacidad, desempata igual que el eje gamer (DDR desc)")
    void aIgualCapacidadDesempataPorDdr() {
        TechSpecs ddr5 = conRam("DDR5", 6000, 32);
        TechSpecs ddr4 = conRam("DDR4", 3200, 32);
        assertThat(EjesTecnicos.RAM_HOMELAB.compare(ddr5, ddr4)).isLessThan(0);
    }

    @Test
    @DisplayName("la abstención de capacidad (0) sigue última")
    void laAbstencionDeCapacidadSigueUltima() {
        TechSpecs conCapacidad = conRam("", 0, 8);
        TechSpecs sinCapacidad = conRam("DDR5", 6000, 0);
        assertThat(EjesTecnicos.RAM_HOMELAB.compare(sinCapacidad, conCapacidad)).isGreaterThan(0);
    }

    // ── ALMACENAMIENTO_DATOS: capacidad primero, HDD preferido a igual capacidad ─

    @Test
    @DisplayName("más capacidad gana, aunque sea HDD contra NVMe")
    void masCapacidadGanaAunqueSeaHdd() {
        TechSpecs hddGrande = conTipoAlmacenamiento(TipoAlmacenamiento.HDD, 4000);
        TechSpecs nvmeChico = conTipoAlmacenamiento(TipoAlmacenamiento.NVME, 500);
        assertThat(EjesTecnicos.ALMACENAMIENTO_DATOS.compare(hddGrande, nvmeChico)).isLessThan(0);
    }

    @Test
    @DisplayName("a igual capacidad, HDD le gana a SSD y a NVMe — el disco de datos prefiere barato/masivo")
    void aIgualCapacidadHddLeGanaASsdYNvme() {
        TechSpecs hdd = conTipoAlmacenamiento(TipoAlmacenamiento.HDD, 2000);
        TechSpecs ssd = conTipoAlmacenamiento(TipoAlmacenamiento.SSD, 2000);
        TechSpecs nvme = conTipoAlmacenamiento(TipoAlmacenamiento.NVME, 2000);
        assertThat(EjesTecnicos.ALMACENAMIENTO_DATOS.compare(hdd, ssd)).isLessThan(0);
        assertThat(EjesTecnicos.ALMACENAMIENTO_DATOS.compare(ssd, nvme)).isLessThan(0);
    }

    @Test
    @DisplayName("la abstención de tecnología (DESCONOCIDO) sigue última a igual capacidad")
    void laAbstencionDeTecnologiaSigueUltima() {
        TechSpecs hdd = conTipoAlmacenamiento(TipoAlmacenamiento.HDD, 1000);
        TechSpecs desconocido = conTipoAlmacenamiento(TipoAlmacenamiento.DESCONOCIDO, 1000);
        assertThat(EjesTecnicos.ALMACENAMIENTO_DATOS.compare(hdd, desconocido)).isLessThan(0);
    }

    @Test
    @DisplayName("D13: la abstención va última AUNQUE tenga más capacidad — un externo no le puede ganar a un interno conocido")
    void laAbstencionDeTecnologiaSigueUltimaAunConMasCapacidad() {
        // Antes de T11 la capacidad corría PRIMERO en este eje, así que un
        // externo (tecnología abstenida) con más GB le ganaba el slot a un
        // interno conocido más chico — un "Disco Duro Externo 1Tb" le ganaba
        // a un SSD interno de 512GB. D13 exige abstención última en TODO eje,
        // antes de mirar cualquier otra magnitud.
        TechSpecs ssdInternoChico = conTipoAlmacenamiento(TipoAlmacenamiento.SSD, 512);
        TechSpecs externoGrande = conTipoAlmacenamiento(TipoAlmacenamiento.DESCONOCIDO, 4000);
        assertThat(EjesTecnicos.ALMACENAMIENTO_DATOS.compare(ssdInternoChico, externoGrande)).isLessThan(0);
    }

    // ── MINI_PC: gama -> nivel desc -> capacidadGb desc, igual que CPU pero con RAM en vez de año ─

    @Test
    @DisplayName("gama manda primero, igual que en CPU")
    void gamaMandaPrimero() {
        TechSpecs alta = conMiniPc(Gama.ALTA, 3, 8);
        TechSpecs baja = conMiniPc(Gama.BAJA, 9, 64);
        assertThat(EjesTecnicos.MINI_PC.compare(alta, baja)).isLessThan(0);
    }

    @Test
    @DisplayName("a igual gama, nivel desc")
    void aIgualGamaNivelDesc() {
        TechSpecs nivel7 = conMiniPc(Gama.ALTA, 7, 8);
        TechSpecs nivel9 = conMiniPc(Gama.ALTA, 9, 8);
        assertThat(EjesTecnicos.MINI_PC.compare(nivel9, nivel7)).isLessThan(0);
    }

    @Test
    @DisplayName("a igual gama y nivel, más RAM gana")
    void aIgualGamaYNivelMasRamGana() {
        TechSpecs pocaRam = conMiniPc(Gama.MEDIA, 5, 8);
        TechSpecs muchaRam = conMiniPc(Gama.MEDIA, 5, 32);
        assertThat(EjesTecnicos.MINI_PC.compare(muchaRam, pocaRam)).isLessThan(0);
    }
}
