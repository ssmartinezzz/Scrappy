package ar.scraper.aggregator.normalize;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GenderResolver#resolver(String, String, String)}.
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 6 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
@Epic("Normalization")
@Feature("Gender")
@DisplayName("GenderResolver — gender inference from raw/nombre/categoria")
class GenderResolverTest {

    private final GenderResolver resolver = new GenderResolver();

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
        Allure.parameter("raw", "Hombre");
        Allure.parameter("nombre", "Calza Nike Training");
        Allure.parameter("categoria", "Calza");
        assertThat(resolver.resolver("Hombre", "Calza Nike Training", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void calzaSinSenalDevuelveMujer() {
        // No raw, no nombre signal — FEMININE_CODED_CATEGORIES fallback produces "mujer".
        Allure.parameter("raw", "");
        Allure.parameter("nombre", "Calza Sport Corta");
        Allure.parameter("categoria", "Calza");
        assertThat(resolver.resolver("", "Calza Sport Corta", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void calzaConDeHombreEnNombreDevuelveHombre() {
        // Explicit masculine signal "de Hombre" in nombre overrides category inference.
        Allure.parameter("raw", "");
        Allure.parameter("nombre", "Calza adidas Techfit De Hombre");
        Allure.parameter("categoria", "Calza");
        assertThat(resolver.resolver("", "Calza adidas Techfit De Hombre", "Calza"))
                .isEqualTo("hombre");
    }

    @Test
    void calzaConHombreComoTokenEnNombreDevuelveHombre() {
        // Standalone "hombre" in nombre (e.g. "Calza Hombre Training") is a valid
        // masculine signal — must NOT be overridden to mujer.
        Allure.parameter("raw", "");
        Allure.parameter("nombre", "Calza Hombre Training");
        Allure.parameter("categoria", "Calza");
        assertThat(resolver.resolver("", "Calza Hombre Training", "Calza"))
                .isEqualTo("hombre");
    }

    @Test
    void leggingConRawHombreDevuelveMujer() {
        // "Legging" normalizes to category Calza — same FEMININE_CODED override applies.
        Allure.parameter("raw", "Hombre");
        Allure.parameter("nombre", "Legging Gym Pro");
        Allure.parameter("categoria", "Calza");
        assertThat(resolver.resolver("Hombre", "Legging Gym Pro", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void polleraConRawHombreDevuelveMujer() {
        // Pollera is feminine-coded; raw="Hombre" from any site must be overridden.
        Allure.parameter("raw", "Hombre");
        Allure.parameter("nombre", "Pollera Mini Tiro Alto");
        Allure.parameter("categoria", "Pollera");
        assertThat(resolver.resolver("Hombre", "Pollera Mini Tiro Alto", "Pollera"))
                .isEqualTo("mujer");
    }

    @Test
    void vestidoConRawHombreDevuelveMujer() {
        Allure.parameter("raw", "Hombre");
        Allure.parameter("nombre", "Vestido Playero");
        Allure.parameter("categoria", "Vestido");
        assertThat(resolver.resolver("Hombre", "Vestido Playero", "Vestido"))
                .isEqualTo("mujer");
    }

    @Test
    void categoriaNoFemeninaConRawHombreDevuelveHombre() {
        // Categories outside FEMININE_CODED_CATEGORIES must NOT be overridden.
        Allure.parameter("raw", "Hombre");
        Allure.parameter("nombre", "Remera Deportiva");
        Allure.parameter("categoria", "Remera");
        assertThat(resolver.resolver("Hombre", "Remera Deportiva", "Remera"))
                .isEqualTo("hombre");
    }

    @Test
    void categoriaNoFemeninaConRawMujerDevuelveMujer() {
        // Verify non-feminine categories still respect raw="mujer" normally.
        Allure.parameter("raw", "mujer");
        Allure.parameter("nombre", "Remera Básica");
        Allure.parameter("categoria", "Remera");
        assertThat(resolver.resolver("mujer", "Remera Básica", "Remera"))
                .isEqualTo("mujer");
    }
}
