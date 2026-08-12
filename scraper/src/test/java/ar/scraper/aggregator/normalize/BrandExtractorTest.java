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
 *
 * <p><b>Rewritten (declared behavior change, CODE-2) by
 * close-1nf-and-3nf-foundation, V19, design DD8</b>: an unmatched brand
 * abstains to {@code ""} — the project-wide abstention sentinel (CODE-5) —
 * and never falls back to the site/store name. A store is not a brand:
 * {@code marca="Bullbenny"} on a jean whose brand nobody recognized was
 * always a lie dressed up as data.</p>
 */
@Epic("Normalization")
@Feature("Brand")
@DisplayName("BrandExtractor — brand extraction from product name/site")
class BrandExtractorTest {

    private final BrandExtractor extractor = new BrandExtractor();

    // ── extraerMarca: no curated match -> abstain, never the site name ─────

    @Test
    void extraerMarcaSinMatchCuradoAbstiene() {
        Allure.parameter("nombre", "Remera Oversize Crop");
        Allure.parameter("sitio", "VCP");
        assertThat(extractor.extraer("Remera Oversize Crop", "VCP")).isEqualTo("");
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
        assertThat(extractor.extraer("Jean [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("");
        assertThat(extractor.extraer("Campera [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("");
    }

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHdcp() {
        Allure.parameter("nombre", "Cable Display Port 8k 60hz Hdr G-sync Hdcp 3 M Vention");
        Allure.parameter("sitio", "Compragamer");
        assertThat(extractor.extraer("Cable Display Port 8k 60hz Hdr G-sync Hdcp 3 M Vention", "Compragamer"))
                .isEqualTo("");
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

    // ── Supplement brands ────────────────────────────────────────────────
    // The curated list held apparel and footwear brands only, so every supplement
    // fell through to the site name: a whey by ENA came back branded "Entreno".
    // SupplementCombo's brand preference could therefore never match anything.

    @Test
    void extraerMarcaReconoceMarcasDeSuplementos() {
        Allure.parameter("sitio", "Entreno");
        assertThat(extractor.extraer("Proteina Whey ENA Sport 1kg", "Entreno")).isEqualTo("ENA");
        assertThat(extractor.extraer("Whey Protein Gold Nutrition 908g", "Entreno")).isEqualTo("Gold Nutrition");
        assertThat(extractor.extraer("Creatina Star Nutrition 300 gr", "Entreno")).isEqualTo("Star Nutrition");
        assertThat(extractor.extraer("Proteina BSA 1 kg", "Entreno")).isEqualTo("BSA");
        assertThat(extractor.extraer("Creatina Monohidrato Xtrenght 300g", "Entreno")).isEqualTo("Xtrenght");
    }

    @Test
    void extraerMarcaNoMatcheaEnaDentroDeOtraPalabra() {
        // Same class of bug as DC inside "Hardcore": a 3-letter brand is only a brand
        // when it stands alone. "Cadena" and "Buena" must not become ENA products.
        Allure.parameter("nombre", "Cadena Buena Onda Acero");
        Allure.parameter("sitio", "Bullbenny");
        assertThat(extractor.extraer("Cadena Buena Onda Acero", "Bullbenny")).isEqualTo("");
    }

    // ── V19/DD8: three brand names are ALSO site names — a match on those
    //    must still win, never be confused with the abstention path ────────

    @Test
    void extraerMarcaMatcheaBulksAunqueTambienSeaUnSitio() {
        Allure.parameter("nombre", "Remera Bulks Oversize");
        Allure.parameter("sitio", "Bulks");
        assertThat(extractor.extraer("Remera Bulks Oversize", "Bulks")).isEqualTo("Bulks");
    }

    @Test
    void extraerMarcaMatcheaHarveyAunqueTambienSeaUnSitio() {
        Allure.parameter("nombre", "Soquete Harvey Willys Ozzy Black");
        Allure.parameter("sitio", "Harvey");
        assertThat(extractor.extraer("Soquete Harvey Willys Ozzy Black", "Harvey")).isEqualTo("Harvey Willys");
    }
}
