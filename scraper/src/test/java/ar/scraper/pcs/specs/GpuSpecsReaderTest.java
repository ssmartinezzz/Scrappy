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

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
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

    // ── RX 9000 (RDNA4): numera por DECENA, como Nvidia — T3a fixed this,
    // T1 had correctly abstained instead of guessing wrong via la regla de
    // centena (medido: 4/24/36 filas reales de 9050/9060/9070) ──────────

    @Test
    void rx9070EsAltaPorDecenaComoNvidia() {
        assertThat(gama("Placa de Video Gigabyte Radeon RX 9070 XT 16GB")).isEqualTo(Gama.ALTA);
        assertThat(gama("Placa de Video Sapphire Radeon RX 9070 GRE 12GB")).isEqualTo(Gama.ALTA);
    }

    @Test
    void rx9060EsMediaPorDecena() {
        assertThat(gama("Placa de Video XFX Radeon RX 9060 XT 8GB")).isEqualTo(Gama.MEDIA);
    }

    @Test
    void rx9050EsBajaPorDecena() {
        assertThat(gama("Placa de Video PowerColor Radeon RX 9050 8GB")).isEqualTo(Gama.BAJA);
    }

    // ── abstencion por campo: GPU llena gama + marcaChip + generacion + VRAM ─

    @Test
    void gpuLlenaGamaMarcaChipGeneracionYVramYAbstieneElResto() {
        TechSpecs t = reader.leer(Tokens.de("Placa de Video Asus GeForce RTX 5090 24GB"));

        assertThat(t.marcaChip()).isEqualTo("NVIDIA");
        assertThat(t.generacion()).isEqualTo(5);
        assertThat(t.capacidadGb()).isEqualTo(24);
        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
        assertThat(t.certificacion()).isEqualTo(ar.scraper.pcs.Certificacion.NINGUNA);
    }

    // ── marcaChip (T3b) ──────────────────────────────────────────────────

    @Test
    void marcaChipNvidia() {
        assertThat(reader.leer(Tokens.de("Placa de Video Zotac GeForce RTX 5060 8GB")).marcaChip())
                .isEqualTo("NVIDIA");
    }

    @Test
    void marcaChipAmd() {
        assertThat(reader.leer(Tokens.de("Placa de Video Sapphire Radeon RX 7900 XTX 24GB")).marcaChip())
                .isEqualTo("AMD");
    }

    @Test
    void marcaChipIntel() {
        assertThat(reader.leer(Tokens.de("Placa de Video Intel ARC A380 6GB")).marcaChip()).isEqualTo("INTEL");
    }

    @Test
    void marcaChipVacioCuandoNoHayMarcaLegible() {
        assertThat(reader.leer(Tokens.de("Placa de Video Generica Sin Modelo")).marcaChip()).isEmpty();
    }

    // ── generacion: digito de los miles del modelo ──────────────────────

    @Test
    void generacionRtx() {
        assertThat(reader.leer(Tokens.de("Placa de Video MSI GeForce RTX 5070 12GB")).generacion()).isEqualTo(5);
        assertThat(reader.leer(Tokens.de("Placa de Video Asus GeForce RTX 4070 12GB")).generacion()).isEqualTo(4);
        assertThat(reader.leer(Tokens.de("Placa de Video Zotac GeForce RTX 3060 12GB")).generacion()).isEqualTo(3);
    }

    @Test
    void generacionGtx() {
        assertThat(reader.leer(Tokens.de("Placa de Video Asus GeForce GTX 1660 6GB")).generacion()).isEqualTo(1);
    }

    @Test
    void generacionRx() {
        assertThat(reader.leer(Tokens.de("Placa de Video Gigabyte Radeon RX 9070 XT 16GB")).generacion())
                .isEqualTo(9);
        assertThat(reader.leer(Tokens.de("Placa de Video PowerColor Radeon RX 7600 8GB")).generacion())
                .isEqualTo(7);
        assertThat(reader.leer(Tokens.de("Placa de Video Sapphire Radeon RX 6900 XT 16GB")).generacion())
                .isEqualTo(6);
    }

    @Test
    void generacionArcAbstiene() {
        assertThat(reader.leer(Tokens.de("Placa de Video Intel ARC A380 6GB")).generacion()).isZero();
    }

    // ── VRAM en capacidadGb ───────────────────────────────────────────────

    @Test
    void vramSeLeeDelPrimerTokenNgb() {
        TechSpecs t = reader.leer(
                Tokens.de("Placa de Video MSI Nvidia GeForce RTX 5070 Ventus 2X 12GB OC GDDR7"));

        assertThat(t.capacidadGb()).isEqualTo(12);
    }

    @Test
    void vramAbstieneCuandoNoHayTokenNgb() {
        assertThat(reader.leer(Tokens.de("Placa de Video Generica Sin Modelo")).capacidadGb()).isZero();
    }

    // ── nivel: la decena del modelo, comparable entre marcas (D1) ────────

    @Test
    void rtx5080EsNivel80() {
        assertThat(leer("Placa de Video ASUS ROG GeForce RTX 5080 16GB GDDR7").nivel()).isEqualTo(80);
    }

    @Test
    void rtx5060EsNivel60() {
        assertThat(leer("Placa de Video MSI GeForce RTX 5060 8GB").nivel()).isEqualTo(60);
    }

    @Test
    void rx9070EsNivel70() {
        // RX 9000 numera por DECENA, como Nvidia — misma ramificación que gama().
        assertThat(leer("Placa de Video ASRock AMD Radeon RX 9070 16GB Challenger").nivel()).isEqualTo(70);
    }

    @Test
    void rx6900EsNivel90() {
        // RX 5000-7000 numera por CENTENA: 6900 es el tope de su serie.
        assertThat(leer("Placa De Video Asrock Phantom Gaming Radeon Rx 6900 Xt 16gb").nivel()).isEqualTo(90);
    }

    @Test
    void rx7600EsNivel60() {
        assertThat(leer("Placa de Video ASRock AMD Radeon RX 7600 Steel Legend 8GB").nivel()).isEqualTo(60);
    }

    @Test
    void gtx1050TiEsNivel50() {
        assertThat(leer("Placa de Video Nvidia GeForce GTX 1050 Ti 4GB").nivel()).isEqualTo(50);
    }

    @Test
    void unNombreSinModeloAbstiene() {
        assertThat(leer("Placa de Video Intel Arc A750 8GB").nivel()).isZero();
    }
}
