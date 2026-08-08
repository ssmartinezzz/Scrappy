package ar.scraper.health;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scraper whose selectors stop matching does not throw — it returns an empty
 * or truncated product list, and the run reports success. The existing check in
 * ScraperService only notices a total zero, and only at INFO level, so a site
 * that silently falls from 800 products to 12 stays invisible indefinitely.
 *
 * <p>This guard compares each site against its own previous yield.
 */
@Epic("Scraping Engine")
@Feature("Run health")
@Story("Site yield collapse")
@DisplayName("SiteYieldGuard — detects a site whose yield collapsed against its own history")
class SiteYieldGuardTest {

    private static final Set<String> ALL = Set.of("midway", "barnes", "freres");

    @Test
    void flagsASiteThatDroppedToZeroWhileItPreviouslyHadProducts() {
        List<SiteYieldGuard.Alerta> alertas = SiteYieldGuard.evaluar(
                Map.of("midway", 800, "barnes", 300),
                Map.of("midway", 0, "barnes", 305),
                Set.of("midway", "barnes"));

        assertThat(alertas).singleElement()
                .satisfies(a -> {
                    assertThat(a.sitio()).isEqualTo("midway");
                    assertThat(a.previo()).isEqualTo(800);
                    assertThat(a.actual()).isZero();
                    assertThat(a.severidad()).isEqualTo(SiteYieldGuard.Severidad.CAIDA_TOTAL);
                });
    }

    @Test
    void flagsAPartialCollapseWhichTheZeroCheckWouldMiss() {
        // The whole point: 12 of 800 is a broken scraper, not a quiet day.
        List<SiteYieldGuard.Alerta> alertas = SiteYieldGuard.evaluar(
                Map.of("midway", 800),
                Map.of("midway", 12),
                Set.of("midway"));

        assertThat(alertas).singleElement()
                .satisfies(a -> assertThat(a.severidad()).isEqualTo(SiteYieldGuard.Severidad.CAIDA_PARCIAL));
    }

    @Test
    void staysQuietForNormalFluctuation() {
        assertThat(SiteYieldGuard.evaluar(
                Map.of("midway", 800),
                Map.of("midway", 730),
                Set.of("midway"))).isEmpty();
    }

    @Test
    void staysQuietWhenAsiteGrows() {
        assertThat(SiteYieldGuard.evaluar(
                Map.of("midway", 800),
                Map.of("midway", 1200),
                Set.of("midway"))).isEmpty();
    }

    @Test
    void ignoresSitesThisRunDidNotScrape() {
        // A run limited to one site must not report the others as collapsed
        // just because they are absent from its results.
        assertThat(SiteYieldGuard.evaluar(
                Map.of("midway", 800, "barnes", 300, "freres", 150),
                Map.of("midway", 790),
                Set.of("midway"))).isEmpty();
    }

    @Test
    void ignoresSitesWithNoPreviousHistory() {
        // A site scraped for the first time has nothing to be compared against.
        assertThat(SiteYieldGuard.evaluar(
                Map.of(),
                Map.of("midway", 0),
                Set.of("midway"))).isEmpty();
    }

    @Test
    void ignoresBaselinesTooSmallToBeMeaningful() {
        // Going from 4 products to 1 is noise, not a signal worth waking anyone.
        assertThat(SiteYieldGuard.evaluar(
                Map.of("midway", 4),
                Map.of("midway", 1),
                Set.of("midway"))).isEmpty();
    }

    @Test
    void reportsEverySiteThatCollapsedNotJustTheFirst() {
        List<SiteYieldGuard.Alerta> alertas = SiteYieldGuard.evaluar(
                Map.of("midway", 800, "barnes", 300, "freres", 150),
                Map.of("midway", 0, "barnes", 20, "freres", 148),
                ALL);

        assertThat(alertas).extracting(SiteYieldGuard.Alerta::sitio)
                .containsExactlyInAnyOrder("midway", "barnes");
    }

    @Test
    void toleratesNullMapsRatherThanBlowingUpTheRun() {
        // This runs at the end of a completed scrape; it must never be the
        // reason a successful run reports failure.
        assertThat(SiteYieldGuard.evaluar(null, Map.of("midway", 0), Set.of("midway"))).isEmpty();
        assertThat(SiteYieldGuard.evaluar(Map.of("midway", 800), null, Set.of("midway"))).isEmpty();
        assertThat(SiteYieldGuard.evaluar(Map.of("midway", 800), Map.of("midway", 0), null)).isEmpty();
    }

    @Test
    void messageNamesTheSiteAndBothCountsSoTheLogIsActionable() {
        SiteYieldGuard.Alerta a = SiteYieldGuard.evaluar(
                Map.of("midway", 800), Map.of("midway", 12), Set.of("midway")).get(0);

        assertThat(a.mensaje()).contains("midway").contains("800").contains("12");
    }

    // ── Surfacing ────────────────────────────────────────────────────────────
    // A log line nobody reads is not detection. Alerts join the per-site error
    // map so they reach /api/data's meta.errores and become visible in the UI.

    @Test
    void alertsAreAddedToThePerSiteErrorMap() {
        Map<String, String> errores = SiteYieldGuard.fusionarEnErrores(
                Map.of(),
                SiteYieldGuard.evaluar(Map.of("midway", 800), Map.of("midway", 0), Set.of("midway")));

        assertThat(errores).containsOnlyKeys("midway");
        assertThat(errores.get("midway")).contains("800");
    }

    @Test
    void aRealScrapeErrorIsNotOverwrittenByAYieldAlert() {
        // The exception explains the collapse; keeping the ratio instead would
        // discard the more specific cause.
        Map<String, String> errores = SiteYieldGuard.fusionarEnErrores(
                Map.of("midway", "TimeoutError: navigation"),
                SiteYieldGuard.evaluar(Map.of("midway", 800), Map.of("midway", 0), Set.of("midway")));

        assertThat(errores.get("midway")).isEqualTo("TimeoutError: navigation");
    }

    @Test
    void mergingIntoAnEmptyAlertListLeavesTheMapUntouched() {
        Map<String, String> original = Map.of("barnes", "boom");

        assertThat(SiteYieldGuard.fusionarEnErrores(original, List.of())).isEqualTo(original);
    }

    @Test
    void mergingToleratesNulls() {
        assertThat(SiteYieldGuard.fusionarEnErrores(null, List.of())).isEmpty();
        assertThat(SiteYieldGuard.fusionarEnErrores(Map.of("a", "b"), null))
                .containsEntry("a", "b");
    }
}
