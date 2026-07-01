package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NormalizerService}'s brand, gender, subcategory,
 * gymrat, and orchestration behavior.
 *
 * <p>Pack/combo unit-count detection tests migrated to
 * {@code ar.scraper.aggregator.normalize.PackQuantityDetectorTest} (Work Unit 4)
 * and category classification tests to
 * {@code ar.scraper.aggregator.normalize.CategoryClassifierTest} (Work Unit 5)
 * of the aggregator SOLID modularization.</p>
 */
class NormalizerServiceTest {

    private final NormalizerService service = new NormalizerService();

    // ── extraerMarca: no capitalized-word fallback, falls back to sitio ────

    @Test
    void extraerMarcaSinMatchCuradoUsaSitio() {
        assertThat(service.extraerMarca("Remera Oversize Crop", "VCP")).isEqualTo("VCP");
    }

    @Test
    void extraerMarcaSinMatchYSinSitioRetornaVacio() {
        assertThat(service.extraerMarca("Remera Oversize Crop", null)).isEqualTo("");
    }

    @Test
    void extraerMarcaCuradaTieneSiemprePrioridad() {
        assertThat(service.extraerMarca("Nike Air Max", "VCP")).isEqualTo("Nike");
    }

    // ── extraerMarca: word-boundary matching, no substring false positives ──
    // Bug real visto en producción: "DC" (2 letras) matcheaba como substring
    // dentro de "Hardcore" y "HDCP", asignando marca "DC" a jeans/camperas/
    // cables que no tienen nada que ver con la marca de skate.

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHardcore() {
        assertThat(service.extraerMarca("Jean [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
        assertThat(service.extraerMarca("Campera [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
    }

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHdcp() {
        assertThat(service.extraerMarca("Cable Display Port 8k 60hz Hdr G-sync Hdcp 3 M Vention", "Compragamer"))
                .isEqualTo("Compragamer");
    }

    @Test
    void extraerMarcaSigueMatcheandoDcComoTokenReal() {
        assertThat(service.extraerMarca("Zapatillas Dc Court Graffik Ss", "City")).isEqualTo("DC");
        assertThat(service.extraerMarca("Botas de Invierno Dc Shoes Crisis 2 Hi", "Dcshoes")).isEqualTo("DC");
    }

    // ══════════════════════════════════════════════════════════════════
    // outfits-v2 — Gender inference: feminine-coded categories
    //
    // Covers the FEMININE_CODED_CATEGORIES override that must fire BEFORE
    // the combined-check (raw+nombre), preventing VTEX catalog-level raw
    // tags like "Hombre" from leaking into women's apparel.
    // ══════════════════════════════════════════════════════════════════

    @Test
    void calzaConRawVtexHombreDevuelveMujer() {
        // VTEX sets raw="Hombre" at category level even for women's calzas.
        // The feminine-coded override must fire before combined.contains("hombre").
        assertThat(service.normalizarGenero("Hombre", "Calza Nike Training", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void calzaSinSenalDevuelveMujer() {
        // No raw, no nombre signal — FEMININE_CODED_CATEGORIES fallback produces "mujer".
        assertThat(service.normalizarGenero("", "Calza Sport Corta", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void calzaConDeHombreEnNombreDevuelveHombre() {
        // Explicit masculine signal "de Hombre" in nombre overrides category inference.
        assertThat(service.normalizarGenero("", "Calza adidas Techfit De Hombre", "Calza"))
                .isEqualTo("hombre");
    }

    @Test
    void calzaConHombreComoTokenEnNombreDevuelveHombre() {
        // Standalone "hombre" in nombre (e.g. "Calza Hombre Training") is a valid
        // masculine signal — must NOT be overridden to mujer.
        assertThat(service.normalizarGenero("", "Calza Hombre Training", "Calza"))
                .isEqualTo("hombre");
    }

    @Test
    void leggingConRawHombreDevuelveMujer() {
        // "Legging" normalizes to category Calza — same FEMININE_CODED override applies.
        assertThat(service.normalizarGenero("Hombre", "Legging Gym Pro", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void polleraConRawHombreDevuelveMujer() {
        // Pollera is feminine-coded; raw="Hombre" from any site must be overridden.
        assertThat(service.normalizarGenero("Hombre", "Pollera Mini Tiro Alto", "Pollera"))
                .isEqualTo("mujer");
    }

    @Test
    void vestidoConRawHombreDevuelveMujer() {
        assertThat(service.normalizarGenero("Hombre", "Vestido Playero", "Vestido"))
                .isEqualTo("mujer");
    }

    @Test
    void categoriaNoFemeninaConRawHombreDevuelveHombre() {
        // Categories outside FEMININE_CODED_CATEGORIES must NOT be overridden.
        assertThat(service.normalizarGenero("Hombre", "Remera Deportiva", "Remera"))
                .isEqualTo("hombre");
    }

    @Test
    void categoriaNoFemeninaConRawMujerDevuelveMujer() {
        // Verify non-feminine categories still respect raw="mujer" normally.
        assertThat(service.normalizarGenero("mujer", "Remera Básica", "Remera"))
                .isEqualTo("mujer");
    }

    // ══════════════════════════════════════════════════════════════════
    // subcategoria-field — resolverSubCategoria 3-tier classification
    // Covers FR-1 (Product 7th legacy ctor, task 5.2) and FR-3 (task 5.1)
    // ══════════════════════════════════════════════════════════════════

    // ── Task 5.2: Product 7th legacy constructor defaults subCategoria ──

    @Test
    void productLegacyConstructorDefaultsSubCategoriaToEmpty() {
        Product p = new Product("Sitio", "Nombre", 1000.0, null,
                "http://url", "http://img", "Remera", "hombre",
                List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
        assertThat(p.subCategoria()).isEqualTo("");
    }

    // ── Task 5.1: resolverSubCategoria tier-1 scenarios ───────────────

    @Test
    void resolverSubCategoriaTier1GorroWithNatacionKeywordReturnsNatacion() {
        // FR-3 scenario: Gorro + natación keyword → "natación"
        assertThat(service.resolverSubCategoria("Gorro de Natación Speedo", "Gorro"))
                .isEqualTo("natación");
    }

    @Test
    void resolverSubCategoriaTier1GorroWithNoKeywordReturnsInvierno() {
        // FR-3 scenario: Gorro with no specific keyword → "invierno" (Gorro default)
        assertThat(service.resolverSubCategoria("Gorro de Lana Azul", "Gorro"))
                .isEqualTo("invierno");
    }

    @Test
    void resolverSubCategoriaTier1MallaWithBikiniKeywordReturnsBikini() {
        // FR-3 scenario: Malla + bikini → "bikini"
        assertThat(service.resolverSubCategoria("Malla Bikini Triangular", "Malla"))
                .isEqualTo("bikini");
    }

    // ── Tier-2 transversal: Medias + hockey keyword in nombre ─────────

    @Test
    void resolverSubCategoriaTier2MediasWithHockeyReturnsHockey() {
        // FR-3 scenario: categoria has no tier-1 hockey match; tier-2 fires
        assertThat(service.resolverSubCategoria("Medias de Hockey Senior", "Medias"))
                .isEqualTo("hockey");
    }

    // ── Tier-2 accent-insensitive: "Padel" (no accent) → "pádel" ──────

    @Test
    void resolverSubCategoriaTier2AccentInsensitivePadelReturnsPadel() {
        // FR-3 scenario: accent-insensitive match
        assertThat(service.resolverSubCategoria("Remera Padel Club", "Remera"))
                .isEqualTo("pádel");
    }

    // ── Word-boundary guard: "espadela" must NOT match "padel" ────────

    @Test
    void resolverSubCategoriaWordBoundaryGuardPreventsEmbeddedMatch() {
        // FR-3 scenario: "padel" embedded mid-word must not false-positive
        assertThat(service.resolverSubCategoria("Raqueta espadela especial", "Accesorio Deportivo"))
                .isEqualTo("");
    }

    // ── Tier-2 running guard: skipped when categoria contains "Running" ─

    @Test
    void resolverSubCategoriaRunningSkippedWhenCategoriaContainsRunning() {
        // FR-3 scenario: running as tier-2 must be skipped for "Zapatilla Running"
        assertThat(service.resolverSubCategoria("Zapatilla Running Adidas", "Zapatilla Running"))
                .isEqualTo("");
    }

    // ── Tier-3 default: no match returns "" ───────────────────────────

    @Test
    void resolverSubCategoriaTier3DefaultReturnsEmpty() {
        // FR-3 scenario: neither tier-1 nor tier-2 matches → ""
        assertThat(service.resolverSubCategoria("Buzo Invierno Premium", "Buzo"))
                .isEqualTo("");
    }

    // ── Botas Snowboarding: gap probe ─────────────────────────────────

    @Test
    void resolverSubCategoriaBotasSnowboarding() {
        // Botas has no tier-1 rules; tier-2 sport fallback fires on "snowboarding"
        assertThat(service.resolverSubCategoria("Botas Snowboarding Nike", "Botas"))
                .isEqualTo("snowboarding");
    }

    // ══════════════════════════════════════════════════════════════════
    // gymrat-por-marca — sportswear brands are gym-eligible apparel even
    // without a training keyword in the name (user-confirmed: brand alone
    // qualifies torso/piernas for the Gym outfit builder). Tradeoff: these
    // items leave the Casual builder, since gym/casual are mutually
    // exclusive on the gymrat flag (OutfitService).
    // ══════════════════════════════════════════════════════════════════

    private Product normalizarUno(String nombre, String marca, String categoria) {
        Product in = new Product("Freres", nombre, 1000.0, null,
                "http://url", "http://img", categoria, "hombre",
                List.of(), Product.MlScore.EMPTY, marca);
        return service.normalizar(List.of(in)).get(0);
    }

    @Test
    void gymratMarcaDeportivaSinKeywordEsGymrat() {
        // "Remera Sportswear" has no training keyword; brand Nike alone qualifies it.
        assertThat(normalizarUno("Remera Sportswear", "Nike", "Remera").gymrat()).isTrue();
        assertThat(normalizarUno("Buzo Reverse Weave", "Champion", "Buzo").gymrat()).isTrue();
        assertThat(normalizarUno("Campera Rival Fleece", "Under Armour", "Campera").gymrat()).isTrue();
    }

    @Test
    void gymratMarcaNoDeportivaSinKeywordNoEsGymrat() {
        // Non-sportswear brand + no training keyword → not gymrat (stays casual-eligible).
        assertThat(normalizarUno("Remera Oversize Básica", "Lacoste", "Remera").gymrat()).isFalse();
    }

    @Test
    void gymratGuardCalzadoGanaSobreMarca() {
        // Calzado hard guard wins even for a sportswear brand — gymrat is ROPA only.
        assertThat(normalizarUno("Zapatillas Air Max", "Nike", "Zapatilla Running").gymrat()).isFalse();
    }
}
