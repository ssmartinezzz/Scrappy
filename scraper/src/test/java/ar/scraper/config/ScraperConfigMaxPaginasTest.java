package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the optional per-site page cap
 * {@code sitio.<nombre>.max_paginas}.
 *
 * <p>Context (add-morashop-and-fix-entreno-pagination, D1): the Tiendanube
 * page loop used to stop at a hardcoded 25 pages shared by every TN site.
 * Entreno's catalogue is 53 pages of 12, so that ceiling silently discarded
 * roughly half of it. The ceiling is now a safety belt with a raised default
 * and a per-site override; the real terminator remains the existing
 * two-consecutive-empty-pages check in {@code TiendanubePage.scrapeJs}.
 *
 * <p>The default itself is NOT defined here. {@code ScraperConfig} parses and
 * validates, the caller supplies the fallback, and the page owns the number —
 * that keeps {@code ar.scraper.pages} out of {@code ar.scraper.config} while
 * still leaving exactly one definition of the default ({@code CODE-6}).
 *
 * <p>Uses the package-private {@link ScraperConfig#ScraperConfig(Properties)}
 * test seam; no classpath config.properties and no Spring context.
 */
@Epic("Configuration")
@Feature("Pagination cap")
@DisplayName("ScraperConfig — per-site max_paginas resolution")
class ScraperConfigMaxPaginasTest {

    private static final int FALLBACK = 60;

    @Step("Load ScraperConfig from in-memory properties")
    private ScraperConfig configWith(Properties p) {
        return new ScraperConfig(p);
    }

    @Step("Build site properties: sitio={nombre}, max_paginas={maxPaginas}")
    private Properties siteProps(String nombre, String maxPaginas) {
        Properties p = new Properties();
        p.setProperty("sitio." + nombre + ".url", "https://example.com/productos/");
        p.setProperty("sitio." + nombre + ".activo", "true");
        if (maxPaginas != null) {
            p.setProperty("sitio." + nombre + ".max_paginas", maxPaginas);
        }
        return p;
    }

    @Test
    @DisplayName("sin la propiedad, devuelve el fallback que le pasa el llamador")
    void absentPropertyFallsBackToCallerDefault() {
        ScraperConfig config = configWith(siteProps("harvey", null));
        assertThat(config.getMaxPaginas("harvey", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("con la propiedad, devuelve el valor configurado")
    void explicitValueWins() {
        ScraperConfig config = configWith(siteProps("entreno", "120"));
        assertThat(config.getMaxPaginas("entreno", FALLBACK)).isEqualTo(120);
    }

    @Test
    @DisplayName("el nombre del sitio se resuelve sin importar el case")
    void siteNameIsCaseInsensitive() {
        // The scraper holds the DISPLAY name ("Entreno"); config keys are the
        // lowercase property name. ScraperFactory derives display by
        // capitalising the lowercased key, so lowercasing here is exact.
        ScraperConfig config = configWith(siteProps("entreno", "90"));
        assertThat(config.getMaxPaginas("Entreno", FALLBACK)).isEqualTo(90);
    }

    @Test
    @DisplayName("un valor no numerico cae al fallback en vez de romper el scrape")
    void nonNumericFallsBack() {
        ScraperConfig config = configWith(siteProps("entreno", "muchas"));
        assertThat(config.getMaxPaginas("entreno", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("cero y negativos caen al fallback — un cap de 0 no scrapearia nada")
    void nonPositiveFallsBack() {
        assertThat(configWith(siteProps("entreno", "0")).getMaxPaginas("entreno", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(configWith(siteProps("entreno", "-3")).getMaxPaginas("entreno", FALLBACK))
                .isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("un sitio sin entrada en config no arrastra el cap de otro")
    void unknownSiteGetsFallbackNotAnotherSitesValue() {
        ScraperConfig config = configWith(siteProps("entreno", "120"));
        assertThat(config.getMaxPaginas("harvey", FALLBACK)).isEqualTo(FALLBACK);
    }
}
