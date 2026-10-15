package ar.scraper.notificaciones;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;

/**
 * A message that crosses the 8 KiB output buffer leaves in two segments; with Nagle on, the second
 * waits for the ACK of the first, which the peer delays by ~40 ms. Every SMTP socket sets TCP_NODELAY.
 */
final class SocketsTcpNoDelay {

    private SocketsTcpNoDelay() {
    }

    static SocketFactory plano() {
        return new Plana(SocketFactory.getDefault());
    }

    /** Wraps {@code base}, which keeps deciding trust; only the socket option is added. */
    static SSLSocketFactory tls(SSLSocketFactory base) {
        return new Tls(base);
    }

    private static <S extends Socket> S sinNagle(S socket) throws IOException {
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static final class Plana extends SocketFactory {
        private final SocketFactory base;

        Plana(SocketFactory base) {
            this.base = base;
        }

        @Override
        public Socket createSocket() throws IOException {
            return sinNagle(base.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return sinNagle(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return sinNagle(base.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return sinNagle(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return sinNagle(base.createSocket(host, port, local, localPort));
        }
    }

    private static final class Tls extends SSLSocketFactory {
        private final SSLSocketFactory base;

        Tls(SSLSocketFactory base) {
            this.base = base;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return sinNagle(base.createSocket());
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            sinNagle(s);
            return sinNagle(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(Socket s, InputStream consumed, boolean autoClose) throws IOException {
            sinNagle(s);
            return sinNagle(base.createSocket(s, consumed, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return sinNagle(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return sinNagle(base.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return sinNagle(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return sinNagle(base.createSocket(host, port, local, localPort));
        }
    }
}
