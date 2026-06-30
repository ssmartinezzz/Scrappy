package ar.scraper.pages;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link BasePage#flattenedShadowHtml()} delegates to
 * {@code page.evaluate()} and returns the result verbatim.
 */
class BasePageShadowDomTest {

    /** Minimal concrete subclass — BasePage has no abstract methods to implement. */
    static class TestPage extends BasePage {
        TestPage(Page page) {
            super(page, 5000);
        }
    }

    @Test
    void flattenedShadowHtmlReturnsPagesResult() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString())).thenReturn("<div>shadow</div>");

        String result = new TestPage(mockPage).flattenedShadowHtml();

        assertThat(result).isEqualTo("<div>shadow</div>");
    }
}
