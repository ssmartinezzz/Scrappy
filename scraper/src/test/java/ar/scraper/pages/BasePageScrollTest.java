package ar.scraper.pages;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link BasePage#scrollToBottom()} uses an img-count JS poll
 * (not a fixed Java-side waitForTimeout loop).
 */
class BasePageScrollTest {

    /** Minimal concrete subclass — BasePage has no abstract methods to implement. */
    static class TestPage extends BasePage {
        TestPage(Page page) {
            super(page, 5000);
        }
    }

    @Test
    void scrollUsesImgCountPoll() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString())).thenReturn(null);

        new TestPage(mockPage).scrollToBottom();

        verify(mockPage, atLeastOnce())
                .evaluate(argThat(s -> s.contains("querySelectorAll('img')")));
    }

    @Test
    void scrollDoesNotUseOldWaitTimeout() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString())).thenReturn(null);

        new TestPage(mockPage).scrollToBottom();

        verify(mockPage, never()).waitForTimeout(anyDouble());
    }
}
