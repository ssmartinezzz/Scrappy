package ar.scraper.scrapers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link BaseScraper#STEALTH_INIT_SCRIPT} contains all required
 * browser fingerprint spoofing properties.
 *
 * These are pure constant assertions — no Playwright instance needed.
 */
class BaseScraperStealthTest {

    @Test
    void stealthScriptContainsWebdriverOverride() {
        assertThat(BaseScraper.STEALTH_INIT_SCRIPT).contains("navigator.webdriver");
    }

    @Test
    void stealthScriptContainsPluginsOverride() {
        assertThat(BaseScraper.STEALTH_INIT_SCRIPT).contains("navigator.plugins");
    }

    @Test
    void stealthScriptContainsLanguagesOverride() {
        assertThat(BaseScraper.STEALTH_INIT_SCRIPT).contains("navigator.languages");
    }

    @Test
    void stealthScriptContainsChromeOverride() {
        assertThat(BaseScraper.STEALTH_INIT_SCRIPT).contains("window.chrome");
    }
}
