package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security")
@Feature("Outbound egress")
@Story("Image enrichment client")
@DisplayName("VaypolPage — the image HttpClient follows redirects only through the egress proxy")
class VaypolPageEgressTest {

    @Test
    void imageClientRoutesEveryRequestThroughTheGivenProxy() {
        var proxy = new InetSocketAddress("127.0.0.1", 4321);

        var client = VaypolPage.imageClient(proxy);

        assertThat(client.proxy()).isPresent();
        assertThat(client.proxy().get().select(URI.create("http://shop.example/p"))).singleElement()
                .satisfies(p -> assertThat(p.address()).isEqualTo(proxy));
        assertThat(client.proxy().get().select(URI.create("https://shop.example/p"))).singleElement()
                .satisfies(p -> assertThat(p.address()).isEqualTo(proxy));
    }

    @Test
    void imageClientStillFollowsRedirects() {
        var client = VaypolPage.imageClient(new InetSocketAddress("127.0.0.1", 1));

        assertThat(client.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NORMAL);
    }
}
