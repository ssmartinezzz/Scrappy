package ar.scraper.aggregator.normalize;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubcategoryResolver#resolver(String, String)} —
 * design: subcategoria-field, FR-3 (task 5.1).
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 7 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
@Epic("Normalization")
@Feature("Category")
@Story("Subcategory")
@DisplayName("SubcategoryResolver — 3-tier activity/sport subcategory resolution")
class SubcategoryResolverTest {

    private final SubcategoryResolver resolver = new SubcategoryResolver();

    // ── Task 5.1: resolverSubCategoria tier-1 scenarios ───────────────

    @Test
    void resolverSubCategoriaTier1GorroWithNatacionKeywordReturnsNatacion() {
        // FR-3 scenario: Gorro + natación keyword → "natación"
        Allure.parameter("nombre", "Gorro de Natación Speedo");
        Allure.parameter("categoria", "Gorro");
        assertThat(resolver.resolver("Gorro de Natación Speedo", "Gorro"))
                .isEqualTo("natación");
    }

    @Test
    void resolverSubCategoriaTier1GorroWithNoKeywordReturnsInvierno() {
        // FR-3 scenario: Gorro with no specific keyword → "invierno" (Gorro default)
        Allure.parameter("nombre", "Gorro de Lana Azul");
        Allure.parameter("categoria", "Gorro");
        assertThat(resolver.resolver("Gorro de Lana Azul", "Gorro"))
                .isEqualTo("invierno");
    }

    @Test
    void resolverSubCategoriaTier1MallaWithBikiniKeywordReturnsBikini() {
        // FR-3 scenario: Malla + bikini → "bikini"
        Allure.parameter("nombre", "Malla Bikini Triangular");
        Allure.parameter("categoria", "Malla");
        assertThat(resolver.resolver("Malla Bikini Triangular", "Malla"))
                .isEqualTo("bikini");
    }

    // ── Tier-2 transversal: Medias + hockey keyword in nombre ─────────

    @Test
    void resolverSubCategoriaTier2MediasWithHockeyReturnsHockey() {
        // FR-3 scenario: categoria has no tier-1 hockey match; tier-2 fires
        Allure.parameter("nombre", "Medias de Hockey Senior");
        Allure.parameter("categoria", "Medias");
        assertThat(resolver.resolver("Medias de Hockey Senior", "Medias"))
                .isEqualTo("hockey");
    }

    // ── Tier-2 accent-insensitive: "Padel" (no accent) → "pádel" ──────

    @Test
    void resolverSubCategoriaTier2AccentInsensitivePadelReturnsPadel() {
        // FR-3 scenario: accent-insensitive match
        Allure.parameter("nombre", "Remera Padel Club");
        Allure.parameter("categoria", "Remera");
        assertThat(resolver.resolver("Remera Padel Club", "Remera"))
                .isEqualTo("pádel");
    }

    // ── Word-boundary guard: "espadela" must NOT match "padel" ────────

    @Test
    void resolverSubCategoriaWordBoundaryGuardPreventsEmbeddedMatch() {
        // FR-3 scenario: "padel" embedded mid-word must not false-positive
        Allure.parameter("nombre", "Raqueta espadela especial");
        Allure.parameter("categoria", "Accesorio Deportivo");
        assertThat(resolver.resolver("Raqueta espadela especial", "Accesorio Deportivo"))
                .isEqualTo("");
    }

    // ── Tier-2 running guard: skipped when categoria contains "Running" ─

    @Test
    void resolverSubCategoriaRunningSkippedWhenCategoriaContainsRunning() {
        // FR-3 scenario: running as tier-2 must be skipped for "Zapatilla Running"
        Allure.parameter("nombre", "Zapatilla Running Adidas");
        Allure.parameter("categoria", "Zapatilla Running");
        assertThat(resolver.resolver("Zapatilla Running Adidas", "Zapatilla Running"))
                .isEqualTo("");
    }

    // ── Tier-3 default: no match returns "" ───────────────────────────

    @Test
    void resolverSubCategoriaTier3DefaultReturnsEmpty() {
        // FR-3 scenario: neither tier-1 nor tier-2 matches → ""
        Allure.parameter("nombre", "Buzo Invierno Premium");
        Allure.parameter("categoria", "Buzo");
        assertThat(resolver.resolver("Buzo Invierno Premium", "Buzo"))
                .isEqualTo("");
    }

    // ── Botas Snowboarding: gap probe ─────────────────────────────────

    @Test
    @DisplayName("Botas + snowboarding keyword falls through to tier-2 sport match")
    void resolverSubCategoriaBotasSnowboarding() {
        // Botas has no tier-1 rules; tier-2 sport fallback fires on "snowboarding"
        Allure.parameter("nombre", "Botas Snowboarding Nike");
        Allure.parameter("categoria", "Botas");
        assertThat(resolver.resolver("Botas Snowboarding Nike", "Botas"))
                .isEqualTo("snowboarding");
    }
}
