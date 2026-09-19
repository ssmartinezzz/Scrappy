package ar.scraper.pcs.specs;

import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GpuSpecsReader — gama by family + model decade/century, not a closed model list")
class GpuSpecsReaderTest {

    private final GpuSpecsReader reader = new GpuSpecsReader();

    private Gama gama(String nombre) {
        return reader.leer(Tokens.de(nombre)).gama();
    }

    @Test
    void categoriaEsGpu() {
        assertThat(reader.categoria()).isEqualTo("GPU");
    }

    // ── ALTA: RTX x090/x080/x070 ─────────────────────────────────────────

    @Test
    void rtx5090EsAlta() {
        assertThat(gama("Placa de Video Asus GeForce RTX 5090 24GB")).isEqualTo(Gama.ALTA);
    }

    @Test
    void rtx5080EsAlta() {
        assertThat(gama("Placa de Video Gigabyte GeForce RTX 5080 16GB")).isEqualTo(Gama.ALTA);
    }

    @Test
    void rtx5070EsAlta() {
        assertThat(gama("Placa de Video MSI GeForce RTX 5070 12GB")).isEqualTo(Gama.ALTA);
    }

    // ── MEDIA: RTX x060 ──────────────────────────────────────────────────

    @Test
    void rtx5060EsMedia() {
        assertThat(gama("Placa de Video Zotac GeForce RTX 5060 8GB")).isEqualTo(Gama.MEDIA);
    }

    // ── BAJA: RTX x050, cualquier GTX, cualquier ARC ───────────────────

    @Test
    void rtx5050EsBaja() {
        assertThat(gama("Placa de Video Palit GeForce RTX 5050 8GB")).isEqualTo(Gama.BAJA);
    }

    @Test
    void cualquierGtxEsBaja() {
        assertThat(gama("Placa de Video Asus GeForce GTX 1650 4GB")).isEqualTo(Gama.BAJA);
    }

    @Test
    void cualquierArcEsBaja() {
        assertThat(gama("Placa de Video Intel ARC A380 6GB")).isEqualTo(Gama.BAJA);
    }

    // ── Radeon: x900/x800 alta, x700/x600 media, x500 y abajo baja ──────

    @Test
    void rx7900EsAlta() {
        assertThat(gama("Placa de Video Sapphire Radeon RX 7900 XTX 24GB")).isEqualTo(Gama.ALTA);
    }

    @Test
    void rx7800EsAlta() {
        assertThat(gama("Placa de Video Sapphire Radeon RX 7800 XT 16GB")).isEqualTo(Gama.ALTA);
    }

    @Test
    void rx7700EsMedia() {
        assertThat(gama("Placa de Video PowerColor Radeon RX 7700 XT 12GB")).isEqualTo(Gama.MEDIA);
    }

    @Test
    void rx7600EsMedia() {
        assertThat(gama("Placa de Video XFX Radeon RX 7600 8GB")).isEqualTo(Gama.MEDIA);
    }

    @Test
    void rx6500EsBaja() {
        assertThat(gama("Placa de Video Sapphire Radeon RX 6500 XT 4GB")).isEqualTo(Gama.BAJA);
    }

    // ── DESCONOCIDA: nada matchea ────────────────────────────────────────

    @Test
    void nombreSinFamiliaNiModeloEsDesconocida() {
        assertThat(gama("Placa de Video Generica Sin Modelo")).isEqualTo(Gama.DESCONOCIDA);
    }

    @Test
    void rx9070NoMatcheaLaTablaDeGamaRadeonYQuedaDesconocida() {
        // RX 9070/9070 XT/9070 GRE (RDNA4) numeran por decena como Nvidia
        // (70 = gama alta en RTX), no por centena como el resto de Radeon
        // (x900/x800/x700/x600/x500). La tabla de la fase 6 no cubre este
        // esquema nuevo — abstenerse es mas seguro que adivinar BAJA
        // tomando "70 <= 500" literal (ver reporte de la tarea).
        assertThat(gama("Placa de Video Gigabyte Radeon RX 9070 XT 16GB")).isEqualTo(Gama.DESCONOCIDA);
        assertThat(gama("Placa de Video Sapphire Radeon RX 9070 GRE 12GB")).isEqualTo(Gama.DESCONOCIDA);
    }

    // ── abstencion por campo: GPU solo llena gama ───────────────────────

    @Test
    void gpuSoloLlenaGama() {
        TechSpecs t = reader.leer(Tokens.de("Placa de Video Asus GeForce RTX 5090 24GB"));

        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
        assertThat(t.certificacion()).isEqualTo(ar.scraper.pcs.Certificacion.NINGUNA);
    }
}
