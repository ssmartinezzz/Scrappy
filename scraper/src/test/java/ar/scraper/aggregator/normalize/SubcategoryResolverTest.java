package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubcategoryResolver#resolver(String, String)} —
 * design: subcategoria-field, FR-3 (task 5.1).
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 7 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
class SubcategoryResolverTest {

    private final SubcategoryResolver resolver = new SubcategoryResolver();

    // ── Task 5.1: resolverSubCategoria tier-1 scenarios ───────────────

    @Test
    void resolverSubCategoriaTier1GorroWithNatacionKeywordReturnsNatacion() {
        // FR-3 scenario: Gorro + natación keyword → "natación"
        assertThat(resolver.resolver("Gorro de Natación Speedo", "Gorro"))
                .isEqualTo("natación");
    }

    @Test
    void resolverSubCategoriaTier1GorroWithNoKeywordReturnsInvierno() {
        // FR-3 scenario: Gorro with no specific keyword → "invierno" (Gorro default)
        assertThat(resolver.resolver("Gorro de Lana Azul", "Gorro"))
                .isEqualTo("invierno");
    }

    @Test
    void resolverSubCategoriaTier1MallaWithBikiniKeywordReturnsBikini() {
        // FR-3 scenario: Malla + bikini → "bikini"
        assertThat(resolver.resolver("Malla Bikini Triangular", "Malla"))
                .isEqualTo("bikini");
    }

    // ── Tier-2 transversal: Medias + hockey keyword in nombre ─────────

    @Test
    void resolverSubCategoriaTier2MediasWithHockeyReturnsHockey() {
        // FR-3 scenario: categoria has no tier-1 hockey match; tier-2 fires
        assertThat(resolver.resolver("Medias de Hockey Senior", "Medias"))
                .isEqualTo("hockey");
    }

    // ── Tier-2 accent-insensitive: "Padel" (no accent) → "pádel" ──────

    @Test
    void resolverSubCategoriaTier2AccentInsensitivePadelReturnsPadel() {
        // FR-3 scenario: accent-insensitive match
        assertThat(resolver.resolver("Remera Padel Club", "Remera"))
                .isEqualTo("pádel");
    }

    // ── Word-boundary guard: "espadela" must NOT match "padel" ────────

    @Test
    void resolverSubCategoriaWordBoundaryGuardPreventsEmbeddedMatch() {
        // FR-3 scenario: "padel" embedded mid-word must not false-positive
        assertThat(resolver.resolver("Raqueta espadela especial", "Accesorio Deportivo"))
                .isEqualTo("");
    }

    // ── Tier-2 running guard: skipped when categoria contains "Running" ─

    @Test
    void resolverSubCategoriaRunningSkippedWhenCategoriaContainsRunning() {
        // FR-3 scenario: running as tier-2 must be skipped for "Zapatilla Running"
        assertThat(resolver.resolver("Zapatilla Running Adidas", "Zapatilla Running"))
                .isEqualTo("");
    }

    // ── Tier-3 default: no match returns "" ───────────────────────────

    @Test
    void resolverSubCategoriaTier3DefaultReturnsEmpty() {
        // FR-3 scenario: neither tier-1 nor tier-2 matches → ""
        assertThat(resolver.resolver("Buzo Invierno Premium", "Buzo"))
                .isEqualTo("");
    }

    // ── Botas Snowboarding: gap probe ─────────────────────────────────

    @Test
    void resolverSubCategoriaBotasSnowboarding() {
        // Botas has no tier-1 rules; tier-2 sport fallback fires on "snowboarding"
        assertThat(resolver.resolver("Botas Snowboarding Nike", "Botas"))
                .isEqualTo("snowboarding");
    }
}
