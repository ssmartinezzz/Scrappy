package ar.scraper.security.egress;

import ar.scraper.security.OutboundAddressPolicy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Loopback HTTP proxy that Chromium is launched through. A {@code page.route} handler only sees the first
 * URL of a redirect chain, so the only place that can veto every hop (redirects, subresources, popups) is
 * the connection itself. The proxy connects to the exact address the policy approved and never resolves
 * the name a second time, which also closes DNS rebinding.
 */
public final class EgressProxy implements AutoCloseable {

    @FunctionalInterface
    public interface DestinationResolver {
        Optional<InetAddress> resolve(String host);
    }

    private static final Logger log = LoggerFactory.getLogger(EgressProxy.class);

    private static final int MAX_HEAD_BYTES = 64 * 1024;
    private static final int HEAD_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int PEER_GRACE_SECONDS = 30;
    private static final int LINGER_MS = 1_000;
    private static final long MAX_DRAIN_BYTES = 256 * 1024;
    private static final int HEAD_END = ('\r' << 24) | ('\n' << 16) | ('\r' << 8) | '\n';
    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");
    private static final Set<String> HOP_BY_HOP =
            Set.of("proxy-connection", "proxy-authorization", "connection", "keep-alive", "upgrade");

    private record Target(String host, int port) {}

    private static final class BadRequest extends Exception {
        final int status;

        BadRequest(int status) {
            super(null, null, false, false);
            this.status = status;
        }
    }

    private final DestinationResolver resolver;
    private ServerSocket listener;

    public EgressProxy(DestinationResolver resolver) {
        this.resolver = resolver;
    }

    private static final class Holder {
        static final EgressProxy INSTANCE = create();

        private static EgressProxy create() {
            EgressProxy p = new EgressProxy(OutboundAddressPolicy.system()::resolvePermitted);
            try {
                p.start();
            } catch (IOException e) {
                throw new IllegalStateException("cannot start the egress proxy", e);
            }
            return p;
        }
    }

    public static EgressProxy shared() {
        return Holder.INSTANCE;
    }

    public synchronized void start() throws IOException {
        if (listener != null) return;
        listener = new ServerSocket(0, 128, InetAddress.getLoopbackAddress());
        ServerSocket accepting = listener;
        Thread acceptor = new Thread(() -> acceptLoop(accepting), "egress-proxy-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) listener.getLocalSocketAddress();
    }

    public String server() {
        return "http://127.0.0.1:" + listener.getLocalPort();
    }

    @Override
    public synchronized void close() throws IOException {
        if (listener != null) listener.close();
    }

    private void acceptLoop(ServerSocket accepting) {
        while (!accepting.isClosed()) {
            try {
                Socket client = accepting.accept();
                Thread.ofVirtual().name("egress-proxy-conn").start(() -> serve(client));
            } catch (IOException e) {
                if (!accepting.isClosed()) log.warn("egress proxy accept failed: {}", e.getMessage());
            }
        }
    }

    private void serve(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(HEAD_TIMEOUT_MS);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream());
            List<String> head = readHead(in);
            client.setSoTimeout(0);
            String[] line = head.get(0).split(" ", -1);
            if (line.length != 3 || !line[2].startsWith("HTTP/1.")) throw new BadRequest(400);
            boolean tunnel = line[0].equals("CONNECT");
            Target target = tunnel ? parseAuthority(line[1]) : parseAbsoluteUri(line[1]);

            Optional<InetAddress> dest = resolver.resolve(target.host());
            if (dest.isEmpty()) {
                log.warn("egress blocked destination host={}", target.host());
                throw new BadRequest(403);
            }
            upstream = new Socket();
            try {
                upstream.connect(new InetSocketAddress(dest.get(), target.port()), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                throw new BadRequest(502);
            }

            OutputStream out = client.getOutputStream();
            if (tunnel) {
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } else {
                upstream.getOutputStream().write(originForm(line, head, target));
                upstream.getOutputStream().flush();
            }
            pipe(client, in, upstream);
        } catch (BadRequest e) {
            reply(client, e.status);
        } catch (IOException e) {
            log.debug("egress proxy connection ended: {}", e.getMessage());
        } finally {
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private static List<String> readHead(InputStream in) throws IOException, BadRequest {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int last4 = 0;
        while (last4 != HEAD_END) {
            int b = in.read();
            if (b == -1) throw new BadRequest(400);
            if (buf.size() >= MAX_HEAD_BYTES) throw new BadRequest(431);
            buf.write(b);
            last4 = (last4 << 8) | b;
        }
        String text = buf.toString(StandardCharsets.ISO_8859_1);
        List<String> lines = List.of(text.substring(0, text.length() - 4).split("\r\n", -1));
        if (lines.get(0).isEmpty()) throw new BadRequest(400);
        return lines;
    }

    private static Target parseAuthority(String authority) throws BadRequest {
        String host;
        String port;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0 || authority.length() <= close + 2 || authority.charAt(close + 1) != ':') throw new BadRequest(400);
            host = authority.substring(1, close);
            port = authority.substring(close + 2);
            if (!IPV6_LITERAL.matcher(host).matches()) throw new BadRequest(400);
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon < 0) throw new BadRequest(400);
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
            if (!HOSTNAME.matcher(host).matches()) throw new BadRequest(400);
        }
        return new Target(host, parsePort(port));
    }

    private static int parsePort(String port) throws BadRequest {
        if (port.isEmpty() || port.length() > 5 || !StringUtils.isNumeric(port)) throw new BadRequest(400);
        int p = Integer.parseInt(port);
        if (p < 1 || p > 65535) throw new BadRequest(400);
        return p;
    }

    private static Target parseAbsoluteUri(String requestTarget) throws BadRequest {
        URI uri;
        try {
            uri = new URI(requestTarget);
        } catch (URISyntaxException e) {
            throw new BadRequest(400);
        }
        if (!"http".equals(StringUtils.lowerCase(uri.getScheme(), Locale.ROOT)) || uri.getHost() == null) {
            throw new BadRequest(400);
        }
        String host = StringUtils.strip(uri.getHost(), "[]");
        boolean valid = uri.getHost().startsWith("[") ? IPV6_LITERAL.matcher(host).matches()
                                                       : HOSTNAME.matcher(host).matches();
        if (!valid) throw new BadRequest(400);
        return new Target(host, uri.getPort() == -1 ? 80 : parsePort(String.valueOf(uri.getPort())));
    }

    private static byte[] originForm(String[] line, List<String> head, Target target) throws BadRequest {
        URI uri = URI.create(line[1]);
        String path = StringUtils.defaultIfEmpty(uri.getRawPath(), "/");
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        StringBuilder sb = new StringBuilder(line[0]).append(' ').append(path).append(query)
                .append(" HTTP/1.1\r\n");
        for (String header : head.subList(1, head.size())) {
            int colon = header.indexOf(':');
            if (colon <= 0) throw new BadRequest(400);
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(name)) sb.append(header).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void pipe(Socket client, InputStream clientIn, Socket upstream) throws IOException {
        CountDownLatch done = new CountDownLatch(2);
        InputStream upstreamIn = upstream.getInputStream();
        Thread.ofVirtual().start(() -> copy(upstreamIn, client, done));
        copy(clientIn, upstream, done);
        try {
            done.await(PEER_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(InputStream from, Socket to, CountDownLatch done) {
        try {
            from.transferTo(to.getOutputStream());
        } catch (IOException ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
            }
            done.countDown();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void reply(Socket client, int status) {
        String reason = switch (status) {
            case 403 -> "Forbidden";
            case 431 -> "Request Header Fields Too Large";
            case 502 -> "Bad Gateway";
            default -> "Bad Request";
        };
        try {
            client.getOutputStream().write(("HTTP/1.1 " + status + " " + reason
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            client.shutdownOutput();
            client.setSoTimeout(LINGER_MS);
            drain(client.getInputStream());
        } catch (IOException ignored) {
        }
    }

    /** Closing with unread request bytes makes the kernel send RST, which can discard the reply we just wrote. */
    private static void drain(InputStream in) throws IOException {
        byte[] sink = new byte[8192];
        long left = MAX_DRAIN_BYTES;
        int n;
        while (left > 0 && (n = in.read(sink)) != -1) left -= n;
    }
}
