package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FuenteSpecsReader — 80 PLUS certificacion, watts kept from phase 1")
class FuenteSpecsReaderTest {

    private final FuenteSpecsReader reader = new FuenteSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsFuente() {
        assertThat(reader.categoria()).isEqualTo("Fuente");
    }

    @Test
    void bronzeYWattsDesdeElMismoNombre() {
        TechSpecs t = leer("Fuente Antec 750W 80 Plus Bronze ATX 3.1 PCIe 5.1");

        assertThat(t.certificacion()).isEqualTo(Certificacion.BRONZE);
        assertThat(t.watts()).isEqualTo(750);
    }

    @Test
    void bronceEnEspanolTambienEsBronze() {
        // Forma real ya presente en el catalogo (ver TechSpecsParserTest):
        // algunos sitios escriben "Bronce" en vez del nombre oficial "Bronze".
        TechSpecs t = leer("Outlet Fuente Aerocool Cylon 600w 80+ Bronce");

        assertThat(t.certificacion()).isEqualTo(Certificacion.BRONZE);
        assertThat(t.watts()).isEqualTo(600);
    }

    @Test
    void goldConSufijoDeVersion() {
        assertThat(leer("Fuente EVGA 650W 80 PLUS Gold V3").certificacion()).isEqualTo(Certificacion.GOLD);
    }

    @Test
    void platinumConSignoMas() {
        assertThat(leer("Fuente Corsair RM850x 850W 80+ Platinum").certificacion()).isEqualTo(Certificacion.PLATINUM);
    }

    @Test
    void white() {
        assertThat(leer("Fuente Cooler Master 500W 80 Plus White").certificacion()).isEqualTo(Certificacion.WHITE);
    }

    @Test
    void silver() {
        assertThat(leer("Fuente EVGA 600W 80 Plus Silver").certificacion()).isEqualTo(Certificacion.SILVER);
    }

    @Test
    void titanium() {
        assertThat(leer("Fuente Seasonic 1000W 80 Plus Titanium").certificacion()).isEqualTo(Certificacion.TITANIUM);
    }

    @Test
    void sinMencionDeCertificacionEsNinguna() {
        // "FB550-LX" no tiene que leerse como una certificacion.
        TechSpecs t = leer("Fuente LNZ 550W FB550-LX");

        assertThat(t.certificacion()).isEqualTo(Certificacion.NINGUNA);
        assertThat(t.watts()).isEqualTo(550);
    }

    // ── abstencion por campo: Fuente solo llena watts + certificacion ────

    @Test
    void fuenteSoloLlenaWattsYCertificacion() {
        TechSpecs t = leer("Fuente Antec 750W 80 Plus Bronze ATX 3.1 PCIe 5.1");

        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
        assertThat(t.gama()).isEqualTo(ar.scraper.pcs.Gama.DESCONOCIDA);
    }
}
