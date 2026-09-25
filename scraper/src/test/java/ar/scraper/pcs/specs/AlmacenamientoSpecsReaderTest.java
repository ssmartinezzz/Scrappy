package ar.scraper.pcs.specs;

import ar.scraper.pcs.EjesTecnicos;
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
        // El fixture era un disco EXTERNO USB; desde que un externo abstiene
        // la tecnología (no es el disco de la PC que se arma), este test
        // necesitaba un disco interno para seguir probando lo que quería
        // probar, que es la lectura del keyword "HDD".
        var t = leer("Disco Interno Hdd Seagate 4TB Sata Iii Skyhawk Surveillance");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.HDD);
        assertThat(t.capacidadGb()).isEqualTo(4096); // 4TB -> 4096GB
    }

    @Test
    void discoDuroEsHdd() {
        // Idem: fixture interno, porque lo que se prueba acá es que "disco
        // duro" lea como HDD, no que un externo lo haga.
        var t = leer("Disco Duro HDD 2TB Western Digital WD Sata III Purple");

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

    @Test
    void unDiscoExternoUsbNoDeclaraLaTecnologiaDelDiscoDeUnaPc() {
        // Un disco externo USB no es el disco de la PC que se está armando.
        // Abstiene en vez de vetarse con una regla nueva, que es la misma
        // política que los pendrives: la abstención es el último escalón del
        // eje, así que se hunde solo y sigue siendo elegible como último
        // recurso. Medido sobre la dev DB (2026-09-22): 18 de 266 filas
        // activas de Almacenamiento son externas, y con un piso de capacidad
        // pedido un "HD HDD EXTERNO 4TB SEAGATE PORTABLE USB 3.0" le ganaba
        // el slot a los discos internos.
        assertThat(leer("HD HDD EXTERNO 4TB SEAGATE PORTABLE USB 3.0").tipoAlmacenamiento())
                .isEqualTo(TipoAlmacenamiento.DESCONOCIDO);
        assertThat(leer("HD SSD EXTERNO 2TB KINGSTON XS2000 USB 3.2 GEN2X2 GRIS").tipoAlmacenamiento())
                .isEqualTo(TipoAlmacenamiento.DESCONOCIDO);
        assertThat(leer("SSD Externo Kingston XS2000 2TB USB-C 2000MB/s").tipoAlmacenamiento())
                .isEqualTo(TipoAlmacenamiento.DESCONOCIDO);
    }

    @Test
    void laCapacidadDeUnDiscoExternoSeSigueLeyendo() {
        // Sólo abstiene la TECNOLOGÍA: la capacidad es un hecho del producto
        // y no depende de dónde se enchufe (fill-only por campo, CODE-5).
        assertThat(leer("HD HDD EXTERNO 4TB SEAGATE PORTABLE USB 3.0").capacidadGb())
                .isEqualTo(4096);
    }

    @Test
    void unDiscoInternoNoSeVeAfectado() {
        assertThat(leer("Disco Solido Ssd 512gb M.2 Sata 2280 Oem").tipoAlmacenamiento())
                .isEqualTo(TipoAlmacenamiento.NVME);
        assertThat(leer("Disco Duro 2TB Seagate Barracuda 7200rpm").tipoAlmacenamiento())
                .isEqualTo(TipoAlmacenamiento.HDD);
    }

    // ── capacidad decimal en TB: el punto se pierde al tokenizar (T10) ───

    @Test
    void capacidadDecimalEnTbSeLeeCorrectamente() {
        // "1.92TB" tokeniza a "1"+"92tb" — el patrón de un solo token leía
        // "92tb" como 92 TB (94208 GB) en vez de 1.92 TB (1966 GB). El punto
        // se pierde en Tokens.array(), así que se recompone desde
        // Tokens.original(), que todavía lo tiene.
        assertThat(leer("HD SSD 1.92TB KINGSTON DC600M SATA III 2.5\" P/SERVIDOR SEDC600M/1920G")
                .capacidadGb()).isEqualTo(1966); // 1.92 * 1024 = 1966.08 -> redondeado

        assertThat(leer("SSD Kingston DC600M 3.84TB Enterprise SATA III").capacidadGb())
                .isEqualTo(3932); // 3.84 * 1024 = 3932.16

        assertThat(leer("SSD Kingston DC600M 7.68TB Enterprise SATA III").capacidadGb())
                .isEqualTo(7864); // 7.68 * 1024 = 7864.32
    }

    @Test
    void capacidadEnteraNoSeVeAfectadaPorElFixDecimal() {
        assertThat(leer("HD SSD 1TB Kingston").capacidadGb()).isEqualTo(1024);
        assertThat(leer("HD HDD 4TB WD BLUE SATA III 3.5\"").capacidadGb()).isEqualTo(4096);
        assertThat(leer("Disco Ssd M.2 Hiksemi 512GB Sata").capacidadGb()).isEqualTo(512);
        assertThat(leer("Disco Ssd M.2 Hiksemi 1024gb Future Lite Ar Pcie 4.0 7000 Mb/s")
                .capacidadGb()).isEqualTo(1024); // "4.0" no es una capacidad: no termina en tb/gb
    }

    // ── T11: un externo no le gana el eje `datos` a un interno conocido ──

    @Test
    void unSsdInternoDe512gbLeGanaAUnExternoDe1tbEnElEjeDatos() {
        // Nombres reales medidos en T8 (dev DB): el externo (1TB = 1024GB)
        // tiene MÁS capacidad cruda que el SSD interno (512GB), y ganaba el
        // slot "datos" porque la capacidad corría antes que la tecnología en
        // EjesTecnicos.ALMACENAMIENTO_DATOS. D13 exige abstención última,
        // sin importar cuánta capacidad declare.
        TechSpecs ssdInterno = leer("HD SSD 512GB LEXAR NQ100 SATA III 2.5\"");
        TechSpecs externo = leer("Disco Duro Externo 1Tb Seagate Portable Drive");

        assertThat(EjesTecnicos.ALMACENAMIENTO_DATOS.compare(ssdInterno, externo)).isLessThan(0);
    }
}
