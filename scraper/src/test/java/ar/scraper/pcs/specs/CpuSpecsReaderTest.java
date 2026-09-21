package ar.scraper.pcs.specs;

import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CpuSpecsReader — gama scale, socket kept from phase 1")
class CpuSpecsReaderTest {

    private final CpuSpecsReader reader = new CpuSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsCpu() {
        assertThat(reader.categoria()).isEqualTo("CPU");
    }

    // ── ALTA: i9/i7, Ryzen 9/7, Ultra 9/7 ───────────────────────────────

    @Test
    void intelI9EsAlta() {
        assertThat(leer("Procesador Intel Core i9 14900K").gama()).isEqualTo(Gama.ALTA);
    }

    @Test
    void intelI7EsAlta() {
        assertThat(leer("Procesador Intel Core i7 14700F").gama()).isEqualTo(Gama.ALTA);
    }

    @Test
    void ryzen9EsAlta() {
        assertThat(leer("Procesador Amd Ryzen 9 7900 Am5").gama()).isEqualTo(Gama.ALTA);
    }

    @Test
    void ryzen7EsAlta() {
        assertThat(leer("Procesador AMD Ryzen 7 8700G").gama()).isEqualTo(Gama.ALTA);
    }

    @Test
    void ultra9EsAlta() {
        assertThat(leer("Procesador Intel Core Ultra 9 285K").gama()).isEqualTo(Gama.ALTA);
    }

    @Test
    void ultra7EsAlta() {
        assertThat(leer("Procesador Intel Core Ultra 7 Socket 1851").gama()).isEqualTo(Gama.ALTA);
    }

    // ── ALTA: X3D gana solo, sin importar la familia numerica ───────────

    @Test
    void x3dEsAltaAunqueLaFamiliaSeaRyzen5() {
        // Un Ryzen 5 X3D no existe hoy en catalogo, pero la regla es "el
        // sufijo gana solo" — no "Ryzen 7/9 con X3D".
        assertThat(leer("Procesador AMD Ryzen 5 5600X3D").gama()).isEqualTo(Gama.ALTA);
    }

    @Test
    void ryzen7800X3dEsAlta() {
        assertThat(leer("Procesador AMD Ryzen 7 7800X3D").gama()).isEqualTo(Gama.ALTA);
    }

    // ── MEDIA: i5, Ryzen 5, Ultra 5 ──────────────────────────────────────

    @Test
    void intelI5EsMedia() {
        assertThat(leer("Procesador Intel Core i5 14400F").gama()).isEqualTo(Gama.MEDIA);
    }

    @Test
    void ryzen5EsMedia() {
        assertThat(leer("Procesador AMD Ryzen 5 5600").gama()).isEqualTo(Gama.MEDIA);
    }

    @Test
    void ultra5EsMedia() {
        assertThat(leer("Procesador Intel Core Ultra 5 245K").gama()).isEqualTo(Gama.MEDIA);
    }

    // ── BAJA: i3, Ryzen 3, Ultra 3, Athlon, Celeron, Pentium ────────────

    @Test
    void intelI3EsBaja() {
        assertThat(leer("Procesador Intel Core i3 12100F").gama()).isEqualTo(Gama.BAJA);
    }

    @Test
    void ryzen3EsBaja() {
        assertThat(leer("Procesador AMD Ryzen 3 4100").gama()).isEqualTo(Gama.BAJA);
    }

    @Test
    void ultra3EsBaja() {
        assertThat(leer("Procesador Intel Core Ultra 3 Socket 1851").gama()).isEqualTo(Gama.BAJA);
    }

    @Test
    void athlonEsBaja() {
        assertThat(leer("Procesador AMD Athlon 3000G").gama()).isEqualTo(Gama.BAJA);
    }

    @Test
    void celeronEsBaja() {
        assertThat(leer("Procesador Intel Celeron G5905").gama()).isEqualTo(Gama.BAJA);
    }

    @Test
    void pentiumEsBaja() {
        assertThat(leer("Procesador Intel Pentium G6400").gama()).isEqualTo(Gama.BAJA);
    }

    // ── DESCONOCIDA: nada matchea, incluido Xeon (servidor, no es esta escala) ─

    @Test
    void xeonEsDesconocida() {
        // Xeon es de servidor y no mapea a esta escala de escritorio —
        // dejarlo DESCONOCIDA en vez de forzarlo a un tier es la abstencion
        // correcta (D2: no se puede afirmar una gama que la tabla no define).
        assertThat(leer("Procesador Intel Xeon E5-2670").gama()).isEqualTo(Gama.DESCONOCIDA);
    }

    @Test
    void nombreSinTierReconocibleEsDesconocida() {
        assertThat(leer("Procesador Generico Sin Modelo").gama()).isEqualTo(Gama.DESCONOCIDA);
    }

    // ── el "/" del catalogo no rompe el parseo ───────────────────────────

    @Test
    void laBarraDeSCollerNoRompeElResto() {
        // Forma real del catalogo, con el typo tal cual aparece.
        TechSpecs t = leer("Procesador AMD Ryzen 5 5600 S/coller Am4");

        assertThat(t.socket()).isEqualTo("AM4");
        assertThat(t.gama()).isEqualTo(Gama.MEDIA);
    }

    // ── abstencion por campo: CPU llena socket + gama + marcaChip + generacion ─

    @Test
    void cpuLlenaSocketGamaMarcaChipYGeneracionYAbstieneElResto() {
        TechSpecs t = leer("Procesador Amd Ryzen 9 7900 Am5");

        assertThat(t.marcaChip()).isEqualTo("AMD");
        assertThat(t.generacion()).isEqualTo(7);
        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
        assertThat(t.certificacion()).isEqualTo(ar.scraper.pcs.Certificacion.NINGUNA);
    }

    // ── marcaChip (T3b) ──────────────────────────────────────────────────

    @Test
    void marcaChipIntel() {
        assertThat(leer("Procesador Intel Core i5 14400F").marcaChip()).isEqualTo("INTEL");
    }

    @Test
    void marcaChipAmd() {
        assertThat(leer("Procesador AMD Ryzen 5 5600").marcaChip()).isEqualTo("AMD");
    }

    @Test
    void marcaChipVacioCuandoNoHayMarcaLegible() {
        assertThat(leer("Procesador Generico Sin Modelo").marcaChip()).isEmpty();
    }

    // ── generacion (T3b) ─────────────────────────────────────────────────

    @Test
    void generacionRyzenEsElMilesDelModelo() {
        assertThat(leer("Micro AMD Ryzen 7 5700X 4.9GHz AM5").generacion()).isEqualTo(5);
        assertThat(leer("Micro AMD Ryzen 9 9950X3D2 Dual Edition 5.6 GHz AM5").generacion()).isEqualTo(9);
        assertThat(leer("Procesador AMD Ryzen 5 8500G").generacion()).isEqualTo(8);
    }

    @Test
    void generacionCoreIDoceATreceCatorceEsElPrimerDosDigitos() {
        assertThat(leer("Micro Intel i7-12700 4.9GHz 25MB S.1700").generacion()).isEqualTo(12);
        assertThat(leer("Procesador Intel Core i5 14600K").generacion()).isEqualTo(14);
        assertThat(leer("Procesador Intel Core i7 14700 5.4GHz Turbo Socket 1700 Raptor Lake").generacion())
                .isEqualTo(14);
    }

    @Test
    void generacionCoreIOchoNueveEsUnDigito() {
        assertThat(leer("Procesador Intel Core i5 9400").generacion()).isEqualTo(9);
    }

    @Test
    void generacionCoreUltraDoscientosEsQuince() {
        // Arrow Lake ("200 series") viene despues de la 14a generacion —
        // no hay un numero de generacion propio en el nombre del fabricante.
        assertThat(leer("Procesador Intel Core Ultra 9 285K").generacion()).isEqualTo(15);
    }

    @Test
    void generacionVaciaCuandoNoHayModeloLegible() {
        assertThat(leer("Procesador Generico Sin Modelo").generacion()).isZero();
    }
}
