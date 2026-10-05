package ar.scraper.security;

import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fail-fast screening of operator-supplied URLs. It is UX, not the defense: DNS names are not resolved
 * here, the egress proxy checks the address actually connected to.
 */
public final class OutboundUrl {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern DOTTED_QUAD = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private OutboundUrl() {}

    public static boolean isAcceptable(String url) {
        if (StringUtils.isBlank(url)) return false;
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String host = hostOf(uri);
        if (StringUtils.isBlank(host) || OutboundAddressPolicy.isLocalhostName(host)) return false;
        if (host.startsWith("[")) return isPublicIpv6(host);
        host = StringUtils.removeEnd(host, ".");
        return looksNumeric(host) ? isPublicDottedQuad(host) : true;
    }

    private static String hostOf(URI uri) {
        if (uri.getHost() != null) return uri.getHost();
        String authority = StringUtils.substringAfterLast(StringUtils.defaultString(uri.getRawAuthority()), "@");
        if (authority.isEmpty()) authority = StringUtils.defaultString(uri.getRawAuthority());
        return authority.startsWith("[") ? StringUtils.substringBefore(authority, "]") + "]"
                                         : StringUtils.substringBefore(authority, ":");
    }

    private static boolean looksNumeric(String host) {
        String[] labels = host.split("\\.", -1);
        boolean allDigits = true;
        for (String label : labels) {
            if (StringUtils.startsWithIgnoreCase(label, "0x")) return true;
            allDigits &= DIGITS.matcher(label).matches();
        }
        return allDigits;
    }

    private static boolean isPublicDottedQuad(String host) {
        return DOTTED_QUAD.matcher(host).matches() && isPublicLiteral(host);
    }

    private static boolean isPublicIpv6(String bracketed) {
        String literal = StringUtils.strip(bracketed, "[]");
        return IPV6_LITERAL.matcher(literal).matches() && isPublicLiteral(literal);
    }

    private static boolean isPublicLiteral(String literal) {
        try {
            return !OutboundAddressPolicy.isInternal(InetAddress.getByName(literal));
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
