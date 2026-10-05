package ar.scraper.security.egress;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security")
@Feature("Outbound egress")
@Story("Egress proxy")
@DisplayName("EgressProxy — connects only to the address the policy approved")
class EgressProxyTest {

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private EgressProxy proxy;

    @BeforeEach
    void startProxy() throws Exception {
        proxy = new EgressProxy(host -> "allowed.test".equals(host)
                ? Optional.of(InetAddress.getLoopbackAddress()) : Optional.empty());
        proxy.start();
        toClose.add(proxy);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : toClose) c.close();
    }

    @Test
    void connectToAnAllowedHostTunnelsBytesBothWays() throws Exception {
        TestServer echo = server(socket -> {
            try (socket) {
                socket.getOutputStream().write(socket.getInputStream().readNBytes(5));
            } catch (IOException ignored) {
            }
        });

        try (Socket client = connectToProxy()) {
            client.getOutputStream().write(("CONNECT allowed.test:" + echo.port() + " HTTP/1.1\r\nHost: allowed.test\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(client.getInputStream())).startsWith("HTTP/1.1 200");

            client.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(client.getInputStream().readNBytes(5), StandardCharsets.US_ASCII)).isEqualTo("hello");
        }
    }

    @Test
    void connectToABlockedHostIsRefusedAndNothingIsContacted() throws Exception {
        TestServer upstream = server(EgressProxyTest::drop);

        try (Socket client = connectToProxy()) {
            client.getOutputStream().write(("CONNECT blocked.test:" + upstream.port() + " HTTP/1.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(client.getInputStream())).startsWith("HTTP/1.1 403");
        }

        Thread.sleep(200);
        assertThat(upstream.connections()).hasValue(0);
    }

    @Test
    void absoluteFormRequestIsRewrittenToOriginFormWithConnectionClose() throws Exception {
        List<String> heads = new CopyOnWriteArrayList<>();
        TestServer upstream = server(socket -> {
            try (socket) {
                heads.add(readHead(socket.getInputStream()));
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException ignored) {
            }
        });

        try (Socket client = connectToProxy()) {
            client.getOutputStream().write(("GET http://allowed.test:" + upstream.port() + "/a?b=1 HTTP/1.1\r\n"
                    + "Host: allowed.test:" + upstream.port() + "\r\nProxy-Connection: keep-alive\r\n"
                    + "Proxy-Authorization: Basic eDp5\r\nConnection: keep-alive\r\nKeep-Alive: 5\r\nAccept: */*\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertThat(response).startsWith("HTTP/1.1 200 OK").endsWith("ok");
        }

        assertThat(heads).hasSize(1);
        String head = heads.get(0);
        assertThat(head).startsWith("GET /a?b=1 HTTP/1.1\r\n")
                .contains("Connection: close\r\n", "Accept: */*\r\n")
                .doesNotContain("Proxy-Connection", "Proxy-Authorization", "keep-alive", "Keep-Alive");
    }

    @Test
    void absoluteFormRequestPipesTheRequestBodyUpstream() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        TestServer upstream = server(socket -> {
            try (socket) {
                readHead(socket.getInputStream());
                bodies.add(new String(socket.getInputStream().readNBytes(4), StandardCharsets.US_ASCII));
                socket.getOutputStream().write("HTTP/1.1 204 No Content\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException ignored) {
            }
        });

        try (Socket client = connectToProxy()) {
            client.getOutputStream().write(("POST http://allowed.test:" + upstream.port() + "/p HTTP/1.1\r\n"
                    + "Content-Length: 4\r\n\r\ndata").getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 204");
        }

        assertThat(bodies).containsExactly("data");
    }

    @Test
    void absoluteFormToABlockedHostIsRefusedAndNothingIsContacted() throws Exception {
        TestServer upstream = server(EgressProxyTest::drop);

        try (Socket client = connectToProxy()) {
            client.getOutputStream().write(("GET http://blocked.test:" + upstream.port() + "/ HTTP/1.1\r\nHost: blocked.test\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(client.getInputStream())).startsWith("HTTP/1.1 403");
        }

        Thread.sleep(200);
        assertThat(upstream.connections()).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "GET", "GET / HTTP/1.1 extra", "CONNECT allowed.test HTTP/1.1",
            "CONNECT allowed.test:0 HTTP/1.1", "CONNECT allowed.test:70000 HTTP/1.1", "CONNECT al!owed.test:80 HTTP/1.1",
            "GET https://allowed.test/ HTTP/1.1", "GET /relative HTTP/1.1", "GET ftp://allowed.test/ HTTP/1.1"})
    void malformedOrUnsupportedRequestsAreAnswered400(String requestLine) throws Exception {
        try (Socket client = connectToProxy()) {
            client.getOutputStream().write((requestLine + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(client.getInputStream())).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    void oversizedRequestHeadIsRefused() throws Exception {
        try (Socket client = connectToProxy()) {
            client.getOutputStream().write(("GET http://allowed.test/ HTTP/1.1\r\nX: " + "a".repeat(70_000) + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(client.getInputStream())).startsWith("HTTP/1.1 431");
        }
    }

    @Test
    void connectFailureIsAnswered502() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        try (Socket client = connectToProxy()) {
            client.getOutputStream().write(("CONNECT allowed.test:" + closedPort + " HTTP/1.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertThat(readHead(client.getInputStream())).startsWith("HTTP/1.1 502");
        }
    }

    @Test
    void serverStringPointsAtTheLoopbackListener() {
        assertThat(proxy.server()).isEqualTo("http://127.0.0.1:" + proxy.address().getPort());
        assertThat(proxy.address().getAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    void sharedInstanceIsASingletonBoundToLoopback() {
        assertThat(EgressProxy.shared()).isSameAs(EgressProxy.shared());
        assertThat(EgressProxy.shared().address().getAddress().isLoopbackAddress()).isTrue();
    }

    private Socket connectToProxy() throws IOException {
        Socket s = new Socket(proxy.address().getAddress(), proxy.address().getPort());
        s.setSoTimeout(10_000);
        toClose.add(s);
        return s;
    }

    private TestServer server(Consumer<Socket> handler) throws IOException {
        TestServer s = new TestServer(handler);
        toClose.add(s);
        return s;
    }

    private static void drop(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            byte[] a = buf.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static final class TestServer implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final AtomicInteger connections = new AtomicInteger();

        TestServer(Consumer<Socket> handler) throws IOException {
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket s = socket.accept();
                        connections.incrementAndGet();
                        Thread.ofVirtual().start(() -> handler.accept(s));
                    }
                } catch (IOException ignored) {
                }
            });
        }

        int port() { return socket.getLocalPort(); }

        AtomicInteger connections() { return connections; }

        @Override
        public void close() throws IOException { socket.close(); }
    }
}
