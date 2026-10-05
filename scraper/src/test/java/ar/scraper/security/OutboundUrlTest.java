package ar.scraper.security;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security")
@Feature("Outbound egress")
@Story("URL screening")
@DisplayName("OutboundUrl — fail-fast screening of operator-supplied URLs")
class OutboundUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://tienda.com.ar", "http://tienda.com.ar/ropa?x=1", "HTTPS://Tienda.com", "https://sub.tienda.com:8443/",
            "https://8.8.8.8/", "http://[2606:4700:4700::1111]/", "https://tienda1.com", "https://1tienda.com"})
    void acceptsPublicHttpUrls(String url) {
        assertThat(OutboundUrl.isAcceptable(url)).as(url).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:5432", "http://169.254.169.254/latest/meta-data", "http://localhost/", "http://LOCALHOST:8080",
            "http://foo.localhost/", "http://10.0.0.1/", "http://192.168.1.1/", "http://172.16.0.1/",
            "http://100.64.0.1/", "http://0.0.0.0/", "http://[::1]/", "http://[fe80::1]/", "http://[fd00::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:169.254.169.254]/"})
    void rejectsInternalTargets(String url) {
        assertThat(OutboundUrl.isAcceptable(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://2130706433/", "http://0x7f.0.0.1/", "http://0x7f000001/", "http://127.1/", "http://0177.0.0.1/",
            "http://127.0.0.01/", "http://1.2.3/", "http://1.2.3.4.5/", "http://256.1.1.1/", "http://8.8.8.08/", "https://0xfoo.example/"})
    void rejectsNonCanonicalNumericHosts(String url) {
        assertThat(OutboundUrl.isAcceptable(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "httpx://tienda.com", "ftp://tienda.com", "file:///etc/passwd", "javascript:alert(1)", "gopher://tienda.com",
            "//tienda.com", "https://", "http:///path", "http://exa mple.com", "https://[::1", "not a url"})
    void rejectsOtherSchemesAndMalformedUrls(String url) {
        assertThat(OutboundUrl.isAcceptable(url)).as(url).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsBlank(String url) {
        assertThat(OutboundUrl.isAcceptable(url)).isFalse();
    }
}
