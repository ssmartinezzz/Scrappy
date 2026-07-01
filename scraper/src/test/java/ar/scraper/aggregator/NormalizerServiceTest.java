package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NormalizerService}'s subcategory resolution, gymrat
 * tagging, and orchestration behavior.
 *
 * <p>Pack/combo unit-count detection tests migrated to
 * {@code ar.scraper.aggregator.normalize.PackQuantityDetectorTest} (Work Unit 4),
 * category classification tests to
 * {@code ar.scraper.aggregator.normalize.CategoryClassifierTest} (Work Unit 5),
 * and brand/gender resolution tests to
 * {@code ar.scraper.aggregator.normalize.BrandExtractorTest}/{@code GenderResolverTest}
 * (Work Unit 6) of the aggregator SOLID modularization.</p>
 */
class NormalizerServiceTest {

    private final NormalizerService service = new NormalizerService();

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
