package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SocketsTcpNoDelay — every SMTP socket is created with TCP_NODELAY")
class SocketsTcpNoDelayTest {

    @Test
    @DisplayName("plain factory: unconnected, host/port and InetAddress sockets all have TCP_NODELAY")
    void fabricaPlana() throws Exception {
        try (ServerSocket servidor = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var fabrica = SocketsTcpNoDelay.plano();
            try (Socket a = fabrica.createSocket();
                 Socket b = fabrica.createSocket("127.0.0.1", servidor.getLocalPort());
                 Socket c = fabrica.createSocket(InetAddress.getLoopbackAddress(), servidor.getLocalPort())) {
                assertThat(a.getTcpNoDelay()).isTrue();
                assertThat(b.getTcpNoDelay()).isTrue();
                assertThat(c.getTcpNoDelay()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("TLS factory: the plain socket it layers TLS over (STARTTLS and SMTPS) has TCP_NODELAY")
    void fabricaTls() throws Exception {
        SSLSocketFactory fabrica = SocketsTcpNoDelay.tls((SSLSocketFactory) SSLSocketFactory.getDefault());
        try (ServerSocket servidor = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             Socket base = new Socket("127.0.0.1", servidor.getLocalPort());
             Socket tls = fabrica.createSocket(base, "127.0.0.1", servidor.getLocalPort(), true)) {
            assertThat(base.getTcpNoDelay()).isTrue();
            assertThat(tls.getTcpNoDelay()).isTrue();
        }
    }

    @Test
    @DisplayName("TLS factory: a socket it connects itself has TCP_NODELAY")
    void fabricaTlsDirecta() throws Exception {
        SSLSocketFactory fabrica = SocketsTcpNoDelay.tls((SSLSocketFactory) SSLSocketFactory.getDefault());
        try (ServerSocket servidor = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             Socket tls = fabrica.createSocket("127.0.0.1", servidor.getLocalPort())) {
            assertThat(tls.getTcpNoDelay()).isTrue();
        }
    }
}
