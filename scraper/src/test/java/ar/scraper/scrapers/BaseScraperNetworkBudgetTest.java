package ar.scraper.scrapers;

import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * The scraper only ever keeps the image <em>URL</em> as a string; it never reads
 * image bytes. Measured against a live Tiendanube listing, letting Chromium
 * fetch them anyway cost 9.5 MB of the 12.7 MB transferred per page.
 *
 * <p>These are constant / interaction assertions — no browser is launched.
 */
@Epic("Scraping Engine")
@Feature("Network budget")
@Story("Blocked resources")
@DisplayName("BaseScraper — heavy resources are actually blocked")
class BaseScraperNetworkBudgetTest {

    @Test
    void launchArgsDisableImageLoading() {
        assertThat(BaseScraper.launchArgs())
                .contains("--blink-settings=imagesEnabled=false");
    }

    @Test
    void launchArgsKeepExistingSandboxAndStealthFlags() {
        assertThat(BaseScraper.launchArgs()).contains(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled");
    }

    @Test
    void routeBlocksAreAppliedToThePage() {
        Page mockPage = Mockito.mock(Page.class);

        BaseScraper.aplicarBloqueosDeRed(mockPage);

        verify(mockPage).route(eq("**/*.{woff,woff2,ttf,otf}"), any(Consumer.class));
        verify(mockPage).route(eq("**/analytics**"), any(Consumer.class));
        verify(mockPage).route(eq("**/gtag**"), any(Consumer.class));
        verify(mockPage).route(eq("**/hotjar**"), any(Consumer.class));
    }
}
