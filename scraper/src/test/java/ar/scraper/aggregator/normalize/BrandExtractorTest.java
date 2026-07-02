package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BrandExtractor#extraer(String, String)}.
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 6 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
class BrandExtractorTest {

    private final BrandExtractor extractor = new BrandExtractor();

    // ── extraerMarca: no capitalized-word fallback, falls back to sitio ────

    @Test
    void extraerMarcaSinMatchCuradoUsaSitio() {
        assertThat(extractor.extraer("Remera Oversize Crop", "VCP")).isEqualTo("VCP");
    }

    @Test
    void extraerMarcaSinMatchYSinSitioRetornaVacio() {
        assertThat(extractor.extraer("Remera Oversize Crop", null)).isEqualTo("");
    }

    @Test
    void extraerMarcaCuradaTieneSiemprePrioridad() {
        assertThat(extractor.extraer("Nike Air Max", "VCP")).isEqualTo("Nike");
    }

    // ── extraerMarca: word-boundary matching, no substring false positives ──
    // Bug real visto en producción: "DC" (2 letras) matcheaba como substring
    // dentro de "Hardcore" y "HDCP", asignando marca "DC" a jeans/camperas/
    // cables que no tienen nada que ver con la marca de skate.

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHardcore() {
        assertThat(extractor.extraer("Jean [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
        assertThat(extractor.extraer("Campera [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
    }

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHdcp() {
        assertThat(extractor.extraer("Cable Display Port 8k 60hz Hdr G-sync Hdcp 3 M Vention", "Compragamer"))
                .isEqualTo("Compragamer");
    }

    @Test
    void extraerMarcaSigueMatcheandoDcComoTokenReal() {
        assertThat(extractor.extraer("Zapatillas Dc Court Graffik Ss", "City")).isEqualTo("DC");
        assertThat(extractor.extraer("Botas de Invierno Dc Shoes Crisis 2 Hi", "Dcshoes")).isEqualTo("DC");
    }
}
