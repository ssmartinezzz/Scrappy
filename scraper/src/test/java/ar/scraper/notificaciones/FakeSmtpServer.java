package ar.scraper.notificaciones;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Just enough SMTP to receive messages in a test; no dependency on a mail-server library. */
final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket socket;
    private final Thread thread;
    private final List<byte[]> mensajes = new CopyOnWriteArrayList<>();
    private final List<String> destinatarios = new CopyOnWriteArrayList<>();
    private final Set<String> temporales = ConcurrentHashMap.newKeySet();
    private final Set<String> rechazados = ConcurrentHashMap.newKeySet();
    private final List<String> comandos = new CopyOnWriteArrayList<>();
    private final AtomicInteger conexiones = new AtomicInteger();
    private volatile boolean rechazaCredenciales;

    FakeSmtpServer() throws IOException {
        this(new ServerSocket(0));
    }

    /** Implicit TLS (SMTPS): the handshake happens before the 220 greeting. */
    FakeSmtpServer(SSLContext tls) throws IOException {
        this(tls.getServerSocketFactory().createServerSocket(0));
    }

    private FakeSmtpServer(ServerSocket socket) {
        this.socket = socket;
        thread = new Thread(this::aceptar, "fake-smtp");
        thread.setDaemon(true);
        thread.start();
    }

    int puerto() {
        return socket.getLocalPort();
    }

    List<byte[]> mensajes() {
        return mensajes;
    }

    List<String> destinatarios() {
        return destinatarios;
    }

    /** Every command line the client sent outside a DATA body, in order. */
    List<String> comandos() {
        return comandos;
    }

    /** Connections accepted so far: a batch that reuses its connection adds exactly one. */
    int conexiones() {
        return conexiones.get();
    }

    /** The server answers 550 to this recipient, as a mailbox that does not exist would. */
    void rechazar(String direccion) {
        rechazados.add(direccion.toLowerCase());
    }

    /** The server answers 451, as a greylisting or overloaded server would. */
    void rechazarTemporalmente(String direccion) {
        temporales.add(direccion.toLowerCase());
    }

    /** Advertises AUTH and answers 535 to any credentials, as a provider that wants an app password does. */
    void rechazarCredenciales() {
        rechazaCredenciales = true;
    }

    private void aceptar() {
        while (!socket.isClosed()) {
            try (Socket cliente = socket.accept()) {
                conexiones.incrementAndGet();
                atender(cliente);
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return;
                }
            }
        }
    }

    private void atender(Socket cliente) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(cliente.getInputStream(), StandardCharsets.ISO_8859_1));
        OutputStream out = cliente.getOutputStream();
        responder(out, "220 fake ESMTP");
        String linea;
        while ((linea = in.readLine()) != null) {
            comandos.add(linea);
            String comando = linea.toUpperCase();
            if (comando.startsWith("EHLO") || comando.startsWith("HELO")) {
                if (rechazaCredenciales) {
                    responder(out, "250-fake");
                    responder(out, "250 AUTH PLAIN LOGIN");
                } else {
                    responder(out, "250 fake");
                }
            } else if (comando.startsWith("AUTH")) {
                responder(out, "535 5.7.8 Username and Password not accepted");
            } else if (comando.startsWith("RCPT TO:")) {
                String direccion = linea.substring(8).replaceAll("[<>\\s]", "");
                destinatarios.add(direccion);
                String clave = direccion.toLowerCase();
                responder(out, rechazados.contains(clave) ? "550 no such user"
                        : temporales.contains(clave) ? "451 try again later" : "250 ok");
            } else if (comando.equals("DATA")) {
                responder(out, "354 go ahead");
                ByteArrayOutputStream datos = new ByteArrayOutputStream();
                while ((linea = in.readLine()) != null && !linea.equals(".")) {
                    datos.write((linea.startsWith("..") ? linea.substring(1) : linea).getBytes(StandardCharsets.ISO_8859_1));
                    datos.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }
                mensajes.add(datos.toByteArray());
                responder(out, "250 queued");
            } else if (comando.equals("QUIT")) {
                responder(out, "221 bye");
                return;
            } else {
                responder(out, "250 ok");
            }
        }
    }

    private static void responder(OutputStream out, String linea) throws IOException {
        out.write((linea + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
