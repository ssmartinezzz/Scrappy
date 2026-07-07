package ar.scraper.aggregator.normalize;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BrandExtractor#extraer(String, String)}.
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 6 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
@Epic("Normalization")
@Feature("Brand")
@DisplayName("BrandExtractor — brand extraction from product name/site")
class BrandExtractorTest {

    private final BrandExtractor extractor = new BrandExtractor();

    // ── extraerMarca: no capitalized-word fallback, falls back to sitio ────

    @Test
    void extraerMarcaSinMatchCuradoUsaSitio() {
        Allure.parameter("nombre", "Remera Oversize Crop");
        Allure.parameter("sitio", "VCP");
        assertThat(extractor.extraer("Remera Oversize Crop", "VCP")).isEqualTo("VCP");
    }

    @Test
    void extraerMarcaSinMatchYSinSitioRetornaVacio() {
        Allure.parameter("nombre", "Remera Oversize Crop");
        Allure.parameter("sitio", (String) null);
        assertThat(extractor.extraer("Remera Oversize Crop", null)).isEqualTo("");
    }

    @Test
    void extraerMarcaCuradaTieneSiemprePrioridad() {
        Allure.parameter("nombre", "Nike Air Max");
        Allure.parameter("sitio", "VCP");
        assertThat(extractor.extraer("Nike Air Max", "VCP")).isEqualTo("Nike");
    }

    // ── extraerMarca: word-boundary matching, no substring false positives ──
    // Bug real visto en producción: "DC" (2 letras) matcheaba como substring
    // dentro de "Hardcore" y "HDCP", asignando marca "DC" a jeans/camperas/
    // cables que no tienen nada que ver con la marca de skate.

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHardcore() {
        Allure.parameter("sitio", "Bullbenny");
        Allure.parameter("nombreJean", "Jean [ Hardcore Desire ] Stone");
        Allure.parameter("nombreCampera", "Campera [ Hardcore Desire ] Stone");
        assertThat(extractor.extraer("Jean [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
        assertThat(extractor.extraer("Campera [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
    }

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHdcp() {
        Allure.parameter("nombre", "Cable Display Port 8k 60hz Hdr G-sync Hdcp 3 M Vention");
        Allure.parameter("sitio", "Compragamer");
        assertThat(extractor.extraer("Cable Display Port 8k 60hz Hdr G-sync Hdcp 3 M Vention", "Compragamer"))
                .isEqualTo("Compragamer");
    }

    @Test
    void extraerMarcaSigueMatcheandoDcComoTokenReal() {
        Allure.parameter("nombreZapatillas", "Zapatillas Dc Court Graffik Ss");
        Allure.parameter("sitioZapatillas", "City");
        Allure.parameter("nombreBotas", "Botas de Invierno Dc Shoes Crisis 2 Hi");
        Allure.parameter("sitioBotas", "Dcshoes");
        assertThat(extractor.extraer("Zapatillas Dc Court Graffik Ss", "City")).isEqualTo("DC");
        assertThat(extractor.extraer("Botas de Invierno Dc Shoes Crisis 2 Hi", "Dcshoes")).isEqualTo("DC");
    }
}
