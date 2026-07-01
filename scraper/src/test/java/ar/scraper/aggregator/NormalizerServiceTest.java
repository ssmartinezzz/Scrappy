package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NormalizerService}'s gymrat tagging and orchestration
 * behavior, plus the {@link Product} legacy-constructor default coverage.
 *
 * <p>Pack/combo unit-count detection tests migrated to
 * {@code ar.scraper.aggregator.normalize.PackQuantityDetectorTest} (Work Unit 4),
 * category classification tests to
 * {@code ar.scraper.aggregator.normalize.CategoryClassifierTest} (Work Unit 5),
 * brand/gender resolution tests to
 * {@code ar.scraper.aggregator.normalize.BrandExtractorTest}/{@code GenderResolverTest}
 * (Work Unit 6), and subcategory resolution tests to
 * {@code ar.scraper.aggregator.normalize.SubcategoryResolverTest} (Work Unit 7)
 * of the aggregator SOLID modularization.</p>
 */
class NormalizerServiceTest {

    private final NormalizerService service = new NormalizerService();

    // ══════════════════════════════════════════════════════════════════
    // subcategoria-field — Product legacy constructor default
    // Covers FR-1 (Product 7th legacy ctor, task 5.2)
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
