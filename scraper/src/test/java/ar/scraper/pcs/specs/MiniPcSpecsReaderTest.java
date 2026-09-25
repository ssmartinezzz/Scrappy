package ar.scraper.pcs.specs;

import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pc-builder-homelab T2 — {@code gama}/{@code nivel}/{@code marcaChip} reuse
 * {@link CpuSpecsReader}'s scale verbatim (CODE-6), so a mini PC ranks
 * against the same axes a CPU does. {@code capacidadGb} reads the RAM
 * capacity — the FIRST standalone "NNGb" token, same convention
 * {@link RamSpecsReader} uses: in this catalog's naming, RAM is stated
 * before storage ("16Gb 480Gb").
 */
@DisplayName("MiniPcSpecsReader — reuses CpuSpecsReader's gama/nivel/marcaChip, reads RAM capacity")
class MiniPcSpecsReaderTest {

    private final MiniPcSpecsReader reader = new MiniPcSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsMiniPc() {
        assertThat(reader.categoria()).isEqualTo("Mini PC");
    }

    @Test
    void ryzen7EsAltaConNivel7YMarcaAmd() {
        TechSpecs specs = leer("Mini Pc Cx Amd Ryzen 7 6800H 16Gb 480Gb Free");
        assertThat(specs.gama()).isEqualTo(Gama.ALTA);
        assertThat(specs.nivel()).isEqualTo(7);
        assertThat(specs.marcaChip()).isEqualTo("AMD");
        assertThat(specs.capacidadGb()).isEqualTo(16);
    }

    @Test
    void coreI5EsMediaConNivel5YMarcaIntel() {
        TechSpecs specs = leer("MINI PC GIGABYTE BRIX CORE I5 10210U S/MEMO S/DISCO");
        assertThat(specs.gama()).isEqualTo(Gama.MEDIA);
        assertThat(specs.nivel()).isEqualTo(5);
        assertThat(specs.marcaChip()).isEqualTo("INTEL");
    }

    @Test
    void barebonesSinRamAbstiene() {
        // "ASUS PN52-BB7000XTC Ryzen 7 5800H" no trae ningún "NNGb" — barebone.
        TechSpecs specs = leer("Mini PC ASUS PN52-BB7000XTC Ryzen 7 5800H");
        assertThat(specs.capacidadGb()).isZero();
        assertThat(specs.gama()).isEqualTo(Gama.ALTA);
    }

    @Test
    void ryzen3EsBajaConNivel3() {
        TechSpecs specs = leer("Mini Pc Jalatec Jt-mpr3 Pc Ryzen 3 Amd 3250c+ 8gb + 256gb");
        assertThat(specs.gama()).isEqualTo(Gama.BAJA);
        assertThat(specs.nivel()).isEqualTo(3);
        assertThat(specs.capacidadGb()).isEqualTo(8);
    }

    @Test
    void unNombreSinCpuLegibleAbstieneGamaYNivel() {
        TechSpecs specs = leer("Mini PC Genérica Sin Marca 8Gb 256Gb");
        assertThat(specs.gama()).isEqualTo(Gama.DESCONOCIDA);
        assertThat(specs.nivel()).isZero();
        assertThat(specs.marcaChip()).isEmpty();
        assertThat(specs.capacidadGb()).isEqualTo(8);
    }
}
