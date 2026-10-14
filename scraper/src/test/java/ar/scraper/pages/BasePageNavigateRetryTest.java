package ar.scraper.pages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Epic("Scraping Engine")
@Feature("Base Page")
@Story("Transient navigation errors")
@DisplayName("BasePage — navigateConReintentos retries only transient network errors")
class BasePageNavigateRetryTest {

    private static final String URL = "https://tienda.test/productos/p/59";

    static class TestPage extends BasePage {
        TestPage(Page page) {
            super(page, 30_000);
        }

        void go(String url) {
            navigateConReintentos(url);
        }
    }

    private static PlaywrightException error(String msg) {
        return new PlaywrightException(msg);
    }

    @Test
    @DisplayName("a net::ERR failure that heals on the next attempt does not surface")
    void transientErrorIsRetried() {
        Page page = Mockito.mock(Page.class);
        doThrow(error("Page.navigate: net::ERR_NETWORK_CHANGED at " + URL))
                .doReturn(null).when(page).navigate(anyString(), any(Page.NavigateOptions.class));

        new TestPage(page).go(URL);

        verify(page, times(2)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    @DisplayName("a persistent net::ERR failure is rethrown after the bounded attempts")
    void persistentTransientErrorGivesUp() {
        Page page = Mockito.mock(Page.class);
        doThrow(error("Page.navigate: net::ERR_NETWORK_CHANGED at " + URL))
                .when(page).navigate(anyString(), any(Page.NavigateOptions.class));

        assertThatThrownBy(() -> new TestPage(page).go(URL))
                .hasMessageContaining("net::ERR_NETWORK_CHANGED");

        verify(page, times(BasePage.NAV_INTENTOS)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    @DisplayName("a timeout is not retried: it would multiply the navigation budget")
    void timeoutIsNotRetried() {
        Page page = Mockito.mock(Page.class);
        doThrow(error("Timeout 30000ms exceeded."))
                .when(page).navigate(anyString(), any(Page.NavigateOptions.class));

        assertThatThrownBy(() -> new TestPage(page).go(URL)).hasMessageContaining("Timeout");

        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    @DisplayName("a closed target is not retried: a closed page does not heal")
    void targetClosedIsNotRetried() {
        Page page = Mockito.mock(Page.class);
        doThrow(error("Target page, context or browser has been closed"))
                .when(page).navigate(anyString(), any(Page.NavigateOptions.class));

        assertThatThrownBy(() -> new TestPage(page).go(URL)).hasMessageContaining("closed");

        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    @DisplayName("a healthy navigation is a single attempt")
    void healthyNavigationIsOneAttempt() {
        Page page = Mockito.mock(Page.class);

        new TestPage(page).go(URL);

        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
        assertThat(BasePage.NAV_INTENTOS).isGreaterThan(1);
    }
}
