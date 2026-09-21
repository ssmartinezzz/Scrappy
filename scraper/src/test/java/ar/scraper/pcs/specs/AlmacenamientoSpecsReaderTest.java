package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AlmacenamientoSpecsReader — new in pc-builder-gama T3b, phase 1 abstained this category entirely")
class AlmacenamientoSpecsReaderTest {

    private final AlmacenamientoSpecsReader reader = new AlmacenamientoSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsAlmacenamiento() {
        assertThat(reader.categoria()).isEqualTo("Almacenamiento");
    }

    // ── NVME: "nvme" o "m.2" (tokeniza a "m" + "2") ──────────────────────

    @Test
    void nvmePorPalabraExplicita() {
        var t = leer("HD SSD 1TB HIKSEMI WAVE M.2 NVME GEN3 3500MB/S 2280");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.NVME);
        assertThat(t.capacidadGb()).isEqualTo(1024); // 1TB -> 1024GB
    }

    @Test
    void nvmePorFormFactorM2SinLaPalabraNvme() {
        var t = leer("Disco Ssd M.2 Hiksemi 1024gb Future Lite Ar Pcie 4.0 7000 Mb/s");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.NVME);
        assertThat(t.capacidadGb()).isEqualTo(1024); // ya viene en GB, no se multiplica
    }

    @Test
    void unNumeroDosSueltoNuncaEsM2() {
        // Ni "Gen4 x4" ni un "2" aislado pueden disparar NVMe por su cuenta
        // — sólo el PAR de tokens "m","2" contiguos lo hace.
        var t = leer("Disco SSD Kingston NV2 480GB Gen4 x4");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.SSD);
    }

    // ── SSD: "ssd" y no es NVMe ───────────────────────────────────────────

    @Test
    void ssdSinM2NiNvmeEsSsd() {
        var t = leer("Disco Solido SSD 960GB Lexar NQ100 SATA III");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.SSD);
        assertThat(t.capacidadGb()).isEqualTo(960);
    }

    // ── HDD: "hdd", "disco duro" o "disco rigido" ────────────────────────

    @Test
    void hddPorPalabraExplicita() {
        var t = leer("HD HDD EXTERNO 4TB WD ELEMENTS USB 3.0");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.HDD);
        assertThat(t.capacidadGb()).isEqualTo(4096); // 4TB -> 4096GB
    }

    @Test
    void discoDuroEsHdd() {
        var t = leer("Disco Duro Externo Seagate 2TB");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.HDD);
    }

    @Test
    void discoRigidoEsHdd() {
        var t = leer("Disco Rigido Interno Toshiba 1TB");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.HDD);
    }

    // ── DESCONOCIDO: pendrives y micro SD, no se vetan ───────────────────

    @Test
    void pendriveQuedaDesconocidoPeroLeeCapacidad() {
        var t = leer("Pendrive 256Gb Kingston USB 3.2 DTX");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.DESCONOCIDO);
        assertThat(t.capacidadGb()).isEqualTo(256);
    }

    @Test
    void microSdQuedaDesconocidoPeroLeeCapacidad() {
        var t = leer("MEMORIA MICROSD 64GB KINGSTON CLASE 10 CANVAS SELECT PLUS G3");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.DESCONOCIDO);
        assertThat(t.capacidadGb()).isEqualTo(64);
    }

    // ── capacidad: ruido real que NO puede leerse como capacidad ─────────

    @Test
    void velocidadDeTransferenciaNoSeLeeComoCapacidad() {
        // "3500MB/S" tokeniza a "3500mb" — el sufijo es "mb", no "gb"/"tb".
        var t = leer("HD SSD 1TB HIKSEMI WAVE M.2 NVME GEN3 3500MB/S 2280");

        // capacidadGb ya se afirmó como 1024 arriba (nvmePorPalabraExplicita);
        // acá sólo interesa que 3500 y 2280 no se cuelen como capacidad.
        assertThat(t.capacidadGb()).isEqualTo(1024);
    }

    @Test
    void modeloConDigitosNoSeLeeComoCapacidad() {
        // "SN3000" no matchea el patrón de capacidad: empieza con letras.
        var t = leer("SSD WD Black SN3000 2TB NVMe Gen4");

        assertThat(t.capacidadGb()).isEqualTo(2048);
    }

    @Test
    void formFactorNumericoNoSeLeeComoCapacidad() {
        // "2280" (22x80mm) no tiene sufijo gb/tb: no puede matchear.
        var t = leer("SSD Generico M.2 2280 NVMe Sin Marca");

        assertThat(t.capacidadGb()).isZero();
    }
}
