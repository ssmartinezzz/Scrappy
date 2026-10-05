package ar.scraper.scrapers;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security")
@Feature("Outbound egress")
@Story("Browser launch")
@DisplayName("BaseScraper — Chromium is launched through the egress proxy")
class BaseScraperLaunchOptionsTest {

    @Test
    void launchOptionsCarryTheProxyServer() {
        var options = BaseScraper.launchOptions(true, "http://127.0.0.1:4321");

        assertThat(options.proxy).isNotNull();
        assertThat(options.proxy.server).isEqualTo("http://127.0.0.1:4321");
    }

    @Test
    void launchOptionsKeepHeadlessAndTheExistingArgs() {
        assertThat(BaseScraper.launchOptions(true, "http://127.0.0.1:1").headless).isTrue();
        assertThat(BaseScraper.launchOptions(false, "http://127.0.0.1:1").headless).isFalse();
        assertThat(BaseScraper.launchOptions(true, "http://127.0.0.1:1").args)
                .containsExactlyElementsOf(BaseScraper.launchArgs());
    }
}
