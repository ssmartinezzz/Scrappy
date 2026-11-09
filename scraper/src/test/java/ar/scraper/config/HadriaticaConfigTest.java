package ar.scraper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("config.properties — Hadriatica is configured with a page budget that covers its catalog")
class HadriaticaConfigTest {

    private static final ScraperConfig CONFIG = new ScraperConfig();

    @Test
    @DisplayName("the site is active on /productos/")
    void siteIsActive() {
        assertThat(CONFIG.getSitiosActivos())
                .filteredOn(s -> "hadriatica".equals(s.nombre()))
                .singleElement()
                .satisfies(s -> assertThat(s.url()).isEqualTo("https://www.hadriatica.com/productos/"));
    }

    @Test
    @DisplayName("max_paginas covers ~486 pages of 12 products, not the TiendanubePage default of 60")
    void pageBudgetCoversTheCatalog() {
        assertThat(CONFIG.getMaxPaginas("hadriatica", 60)).isGreaterThanOrEqualTo(500);
    }
}
