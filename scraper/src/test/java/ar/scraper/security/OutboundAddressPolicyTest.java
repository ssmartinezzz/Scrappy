package ar.scraper.security;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security")
@Feature("Outbound egress")
@Story("Address policy")
@DisplayName("OutboundAddressPolicy — internal address classification and host resolution")
class OutboundAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.8.9.10", "0.0.0.0", "0.1.2.3", "169.254.169.254", "169.254.0.1",
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "224.0.0.1", "239.255.255.255", "100.64.0.1", "100.127.255.255",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:192.168.0.1"})
    void internalAddressesAreInternal(String literal) throws UnknownHostException {
        assertThat(OutboundAddressPolicy.isInternal(InetAddress.getByName(literal))).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1",
            "169.253.0.1", "192.169.0.1", "11.0.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888",
            "::ffff:8.8.8.8"})
    void publicAddressesAreNotInternal(String literal) throws UnknownHostException {
        assertThat(OutboundAddressPolicy.isInternal(InetAddress.getByName(literal))).as(literal).isFalse();
    }

    @Test
    void resolvesToTheFirstAddressWhenEveryAddressIsPublic() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        var policy = new OutboundAddressPolicy(h -> new InetAddress[]{first, InetAddress.getByName("1.1.1.1")});

        assertThat(policy.resolvePermitted("shop.example")).contains(first);
    }

    @Test
    void refusesWhenAnyResolvedAddressIsInternal() throws Exception {
        var policy = new OutboundAddressPolicy(h -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.5")});

        assertThat(policy.resolvePermitted("rebind.example")).isEmpty();
    }

    @Test
    void refusesWhenResolutionFails() {
        var policy = new OutboundAddressPolicy(h -> { throw new UnknownHostException(h); });

        assertThat(policy.resolvePermitted("nx.example")).isEmpty();
    }

    @Test
    void refusesWhenResolutionReturnsNothing() {
        assertThat(new OutboundAddressPolicy(h -> new InetAddress[0]).resolvePermitted("x.example")).isEmpty();
        assertThat(new OutboundAddressPolicy(h -> null).resolvePermitted("x.example")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "LOCALHOST", "foo.localhost", "a.b.LocalHost", "", "   "})
    void localhostAndBlankNeverReachTheResolver(String host) {
        List<String> asked = new ArrayList<>();
        var policy = new OutboundAddressPolicy(h -> {
            asked.add(h);
            return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        });

        assertThat(policy.resolvePermitted(host)).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void nullHostIsRefused() {
        assertThat(new OutboundAddressPolicy(h -> new InetAddress[0]).resolvePermitted(null)).isEmpty();
    }

    @Test
    void systemPolicyRefusesLoopbackLiteralWithoutDns() {
        assertThat(OutboundAddressPolicy.system().resolvePermitted("127.0.0.1")).isEmpty();
        assertThat(OutboundAddressPolicy.system().resolvePermitted("169.254.169.254")).isEmpty();
    }
}
