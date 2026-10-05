package ar.scraper.security;

import org.apache.commons.lang3.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Decides which destinations the scraper may reach. The caller must connect to the address returned
 * by {@link #resolvePermitted}, never re-resolve the name, otherwise DNS rebinding defeats the check.
 */
public final class OutboundAddressPolicy {

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    public OutboundAddressPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    public static OutboundAddressPolicy system() {
        return new OutboundAddressPolicy(InetAddress::getAllByName);
    }

    public static boolean isLocalhostName(String host) {
        String h = StringUtils.removeEnd(StringUtils.trimToEmpty(host).toLowerCase(java.util.Locale.ROOT), ".");
        return h.equals("localhost") || h.endsWith(".localhost");
    }

    public static boolean isInternal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && (second & 0xc0) == 64);
        }
        return a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc;
    }

    public Optional<InetAddress> resolvePermitted(String host) {
        if (StringUtils.isBlank(host) || isLocalhostName(host)) return Optional.empty();
        InetAddress[] all;
        try {
            all = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
        if (all == null || all.length == 0) return Optional.empty();
        for (InetAddress a : all) {
            if (isInternal(a)) return Optional.empty();
        }
        return Optional.of(all[0]);
    }
}
