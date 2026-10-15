package ar.scraper.notificaciones;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Comparator;

/** A throwaway certificate for 127.0.0.1, made with the JDK's own keytool; trusted only by the test that holds it. */
final class CertificadoAutofirmado {

    private static final char[] CLAVE = "changeit".toCharArray();

    private final SSLContext servidor;
    private final SSLSocketFactory clienteQueConfia;

    private CertificadoAutofirmado(SSLContext servidor, SSLSocketFactory clienteQueConfia) {
        this.servidor = servidor;
        this.clienteQueConfia = clienteQueConfia;
    }

    static CertificadoAutofirmado generar() throws Exception {
        Path dir = Files.createTempDirectory("smtps-test");
        try {
            Path almacen = dir.resolve("servidor.p12");
            Process keytool = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=localhost", "-ext", "san=ip:127.0.0.1,dns:localhost",
                    "-storetype", "PKCS12", "-keystore", almacen.toString(),
                    "-storepass", new String(CLAVE), "-keypass", new String(CLAVE))
                    .redirectErrorStream(true).start();
            String salida;
            try (InputStream in = keytool.getInputStream()) {
                salida = new String(in.readAllBytes());
            }
            if (keytool.waitFor() != 0) {
                throw new IOException("keytool falló: " + salida);
            }
            KeyStore claves = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(almacen)) {
                claves.load(in, CLAVE);
            }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(claves, CLAVE);
            SSLContext servidor = SSLContext.getInstance("TLS");
            servidor.init(km.getKeyManagers(), null, new SecureRandom());

            KeyStore confianza = KeyStore.getInstance("PKCS12");
            confianza.load(null, null);
            confianza.setCertificateEntry("smtp", claves.getCertificate("smtp"));
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(confianza);
            SSLContext cliente = SSLContext.getInstance("TLS");
            cliente.init(null, tm.getTrustManagers(), new SecureRandom());
            return new CertificadoAutofirmado(servidor, cliente.getSocketFactory());
        } finally {
            try (var rutas = Files.walk(dir)) {
                rutas.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    SSLContext contextoDelServidor() {
        return servidor;
    }

    SSLSocketFactory fabricaQueConfia() {
        return clienteQueConfia;
    }
}
