package ar.scraper.pages;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The networkidle wait is best-effort — its timeout is caught and ignored —
 * yet it was given the full 30 s page timeout. Pages that never reach network
 * idle therefore burned the whole budget on every page of pagination.
 *
 * <p>Measured on one listing page each: freres and sporting both spent
 * 30 011 ms there, while vcp, harvey, tussy and midway settled in
 * 1 963-3 601 ms. Two of six sites paid the ceiling; the healthy four leave
 * plenty of room under a cap.
 */
@Epic("Scraping Engine")
@Feature("Base Page")
@Story("Navigation budget")
@DisplayName("BasePage — networkidle is bounded independently of the page timeout")
class BasePageNavigateBudgetTest {

    private static final int PAGE_TIMEOUT_MS = 30_000;

    static class TestPage extends BasePage {
        TestPage(Page page) {
            super(page, PAGE_TIMEOUT_MS);
        }

        void go(String url) {
            navigateTo(url);
        }
    }

    private Page.WaitForLoadStateOptions capturedIdleOptions(Page mockPage) {
        new TestPage(mockPage).go("https://tienda.test/productos");

        ArgumentCaptor<Page.WaitForLoadStateOptions> opts =
                ArgumentCaptor.forClass(Page.WaitForLoadStateOptions.class);
        verify(mockPage).waitForLoadState(eq(LoadState.NETWORKIDLE), opts.capture());
        return opts.getValue();
    }

    @Test
    void networkIdleWaitIsCappedWellBelowThePageTimeout() {
        Page.WaitForLoadStateOptions opts = capturedIdleOptions(Mockito.mock(Page.class));

        assertThat(opts.timeout).isEqualTo((double) BasePage.NETWORK_IDLE_MAX_MS);
        assertThat(BasePage.NETWORK_IDLE_MAX_MS).isLessThan(PAGE_TIMEOUT_MS);
    }

    @Test
    void capLeavesHeadroomOverTheSlowestHealthySiteMeasured() {
        // vcp was the slowest site that genuinely settles, at 3 601 ms. A cap
        // at or below that would start truncating sites that were fine.
        assertThat(BasePage.NETWORK_IDLE_MAX_MS).isGreaterThan(3_601);
    }

    @Test
    void navigationItselfStillGetsTheFullPageTimeout() {
        Page mockPage = Mockito.mock(Page.class);

        new TestPage(mockPage).go("https://tienda.test/productos");

        ArgumentCaptor<Page.NavigateOptions> opts =
                ArgumentCaptor.forClass(Page.NavigateOptions.class);
        verify(mockPage).navigate(anyString(), opts.capture());
        assertThat(opts.getValue().timeout).isEqualTo((double) PAGE_TIMEOUT_MS);
    }

    @Test
    void aNetworkIdleTimeoutIsSwallowedSoScrapingContinues() {
        Page mockPage = Mockito.mock(Page.class);
        doThrow(new RuntimeException("Timeout 8000ms exceeded"))
                .when(mockPage).waitForLoadState(eq(LoadState.NETWORKIDLE), any());

        // The whole point of the cap is that reaching it is normal, not fatal.
        new TestPage(mockPage).go("https://tienda.test/productos");
    }
}
