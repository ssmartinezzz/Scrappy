package ar.scraper.pages;

import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the scroll heuristic's <em>coverage</em>, not just its shape.
 *
 * <p>The previous heuristic advanced 600 px per poll and gave up after 20 polls,
 * so it could never travel past 12 000 px. Measured against a live Tiendanube
 * listing it stopped at 4 378 px of an 8 653 px page and saw 139 images; a
 * viewport-sized step that waits for the page to settle reached the true bottom
 * and saw 170 — the old heuristic was silently dropping products.
 */
@Epic("Scraping Engine")
@Feature("Base Page")
@Story("Scroll")
@DisplayName("BasePage — scroll reaches the real bottom of lazy-loading grids")
class BasePageScrollCoverageTest {

    static class TestPage extends BasePage {
        TestPage(Page page) {
            super(page, 5000);
        }
    }

    private String capturedScrollJs() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString())).thenReturn(null);

        new TestPage(mockPage).scrollToBottom();

        ArgumentCaptor<String> js = ArgumentCaptor.forClass(String.class);
        verify(mockPage).evaluate(js.capture());
        return js.getValue();
    }

    @Test
    void scrollStepIsAFullViewportNotAFixed600px() {
        String js = capturedScrollJs();

        assertThat(js).contains("window.innerHeight");
        assertThat(js).doesNotContain("scrollBy(0, 600)");
    }

    @Test
    void scrollTerminatesOnReachingTheBottomNotOnAPollCeiling() {
        String js = capturedScrollJs();

        // Growth of the document itself must be observed — an img-count plateau
        // alone fires while a lazy grid is still appending rows.
        assertThat(js).contains("scrollHeight");
        assertThat(js).contains("scrollY");
    }

    @Test
    void scrollStillPollsImageCount() {
        assertThat(capturedScrollJs()).contains("querySelectorAll('img')");
    }

    @Test
    void scrollWaitsLongEnoughForLazyLoadersToRespond() {
        // 250 ms x 4 stable polls = 1 s of quiet. Measured: 1 s reaches the same
        // 170 images that 2 s and 3 s do, so this is the cheapest sufficient
        // setting, while 400 ms x 3 terminated early at 29 images.
        assertThat(BasePage.SCROLL_POLL_MS).isBetween(150, 300);
        assertThat(BasePage.SCROLL_STABLE_POLLS * BasePage.SCROLL_POLL_MS)
                .isGreaterThanOrEqualTo(1000);
    }

    @Test
    void scrollIsBoundedSoAnInfiniteFeedCannotHangTheRun() {
        assertThat(BasePage.SCROLL_MAX_MS).isPositive().isLessThanOrEqualTo(30000);
        assertThat(capturedScrollJs()).contains(String.valueOf(BasePage.SCROLL_MAX_MS));
    }
}
