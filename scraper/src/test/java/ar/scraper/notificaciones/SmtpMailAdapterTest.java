package ar.scraper.notificaciones;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SmtpMailAdapter — a multipart/alternative message over real SMTP")
class SmtpMailAdapterTest {

    private static final CorreoRenderizado CORREO =
            new CorreoRenderizado("Bajó de precio: 1 favorito", "<html><body><b>hola</b> ñandú</body></html>", "hola ñandú");

    @Test
    @DisplayName("delivers one message with a text part and an HTML part, from the configured sender")
    void entregaMultipart() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "", "avisos@example.com")
                    .enviar(List.of(new CorreoDirigido("usuario@example.com", CORREO)));

            assertThat(smtp.destinatarios()).containsExactly("usuario@example.com");
            MimeMessage mensaje = parsear(smtp.mensajes().getFirst());
            assertThat(mensaje.getSubject()).isEqualTo("Bajó de precio: 1 favorito");
            assertThat(mensaje.getFrom()[0].toString()).isEqualTo("avisos@example.com");
            String texto = contenidoDe(mensaje, "text/plain");
            String html = contenidoDe(mensaje, "text/html");
            assertThat(texto).contains("hola ñandú");
            assertThat(html).contains("<b>hola</b>", "ñandú");
        }
    }

    @Test
    @DisplayName("falls back to the SMTP username as sender when no from-address is set")
    void remitenteDeRespaldo() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "cuenta@example.com", "", "")
                    .enviar(List.of(new CorreoDirigido("usuario@example.com", CORREO)));

            assertThat(parsear(smtp.mensajes().getFirst()).getFrom()[0].toString()).isEqualTo("cuenta@example.com");
        }
    }

    @Test
    @DisplayName("an unreachable server fails every recipient of the batch instead of throwing")
    void fallaSiNoHayServidor() throws Exception {
        int puertoCerrado;
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            puertoCerrado = smtp.puerto();
        }
        SmtpMailAdapter adapter = new SmtpMailAdapter("127.0.0.1", puertoCerrado, "", "", "avisos@example.com");

        Map<String, Entrega> resultado = adapter.enviar(List.of(
                new CorreoDirigido("uno@example.com", CORREO), new CorreoDirigido("dos@example.com", CORREO)));

        assertThat(resultado).containsOnlyKeys("uno@example.com", "dos@example.com");
        assertThat(resultado.values()).noneMatch(Entrega::enviada).allSatisfy(e -> assertThat(e.error()).isNotBlank());
    }

    @Test
    @DisplayName("a whole batch goes over ONE connection and every recipient is reported delivered")
    void unaConexionPorLote() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            Map<String, Entrega> resultado = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "", "avisos@example.com")
                    .enviar(List.of(new CorreoDirigido("uno@example.com", CORREO),
                            new CorreoDirigido("dos@example.com", CORREO),
                            new CorreoDirigido("tres@example.com", CORREO)));

            assertThat(smtp.conexiones()).isEqualTo(1);
            assertThat(smtp.destinatarios()).containsExactly("uno@example.com", "dos@example.com", "tres@example.com");
            assertThat(resultado.values()).hasSize(3).allMatch(Entrega::enviada);
        }
    }

    @Test
    @DisplayName("a recipient the server rejects fails alone: the others in the batch are still delivered")
    void unRechazoNoArrastraAlResto() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            smtp.rechazar("malo@example.com");

            Map<String, Entrega> resultado = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "", "avisos@example.com")
                    .enviar(List.of(new CorreoDirigido("uno@example.com", CORREO),
                            new CorreoDirigido("malo@example.com", CORREO),
                            new CorreoDirigido("tres@example.com", CORREO)));

            assertThat(resultado.get("uno@example.com").enviada()).isTrue();
            assertThat(resultado.get("tres@example.com").enviada()).isTrue();
            assertThat(resultado.get("malo@example.com").enviada()).isFalse();
            assertThat(resultado.get("malo@example.com").permanente()).as("550 is permanent").isTrue();
            assertThat(smtp.mensajes()).hasSize(2);
            assertThat(smtp.conexiones()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the adapter wires the NODELAY factories for plain SMTP and for TLS, with or without a test trust factory")
    void socketsSinDemora() {
        Properties props = new SmtpMailAdapter("smtp.example.com", 587, "u", "p", "a@example.com").propiedades();
        Properties smtps = new SmtpMailAdapter("smtp.example.com", 465, "u", "p", "a@example.com").propiedades();

        assertThat(props.get("mail.smtp.socketFactory")).isNotNull();
        assertThat(props.get("mail.smtp.ssl.socketFactory")).isNotNull();
        assertThat(smtps.get("mail.smtp.ssl.socketFactory")).isNotNull();
    }

    @Test
    @DisplayName("a message over 8 KiB does not stall on Nagle + delayed ACK: 20 of them average well under 20 ms each")
    void mensajeGrandeNoSeDemora() throws Exception {
        String html = "<html><body>" + "x".repeat(9000) + "</body></html>";
        List<CorreoDirigido> lote = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lote.add(new CorreoDirigido("u" + i + "@example.com", new CorreoRenderizado("asunto", html, "texto")));
        }
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            SmtpMailAdapter adaptador = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "", "avisos@example.com");
            long inicio = System.nanoTime();
            adaptador.enviar(lote);
            double msPorMail = (System.nanoTime() - inicio) / 1e6 / lote.size();

            assertThat(smtp.mensajes()).hasSize(20);
            assertThat(smtp.mensajes().getFirst().length).isGreaterThan(8192);
            assertThat(msPorMail).as("ms per mail").isLessThan(20.0);
        }
    }

    @Test
    @DisplayName("port 465 means implicit TLS (SMTPS); any other port negotiates STARTTLS and never forces SSL")
    void smtpsEnElPuerto465() {
        Properties smtps = new SmtpMailAdapter("smtp.example.com", 465, "u", "p", "a@example.com").propiedades();
        Properties starttls = new SmtpMailAdapter("smtp.example.com", 587, "u", "p", "a@example.com").propiedades();

        assertThat(smtps.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(smtps.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(starttls.getProperty("mail.smtp.ssl.enable")).isNotEqualTo("true");
        assertThat(starttls.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    }

    @Test
    @DisplayName("SMTPS: a multipart message arrives over a real TLS handshake when the test trusts the certificate")
    void entregaPorTlsImplicito() throws Exception {
        CertificadoAutofirmado certificado = CertificadoAutofirmado.generar();
        try (FakeSmtpServer smtp = new FakeSmtpServer(certificado.contextoDelServidor())) {
            Map<String, Entrega> resultado = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "",
                    "avisos@example.com", 465, certificado.fabricaQueConfia())
                    .enviar(List.of(new CorreoDirigido("usuario@example.com", CORREO)));

            assertThat(resultado.get("usuario@example.com").enviada()).isTrue();
            MimeMessage mensaje = parsear(smtp.mensajes().getFirst());
            assertThat(mensaje.getSubject()).isEqualTo("Bajó de precio: 1 favorito");
            assertThat(contenidoDe(mensaje, "text/plain")).contains("hola ñandú");
            assertThat(contenidoDe(mensaje, "text/html")).contains("<b>hola</b>");
        }
    }

    @Test
    @DisplayName("SMTPS with the production trust store rejects a self-signed certificate: nothing is delivered")
    void rechazaUnCertificadoQueNadieAvala() throws Exception {
        CertificadoAutofirmado certificado = CertificadoAutofirmado.generar();
        try (FakeSmtpServer smtp = new FakeSmtpServer(certificado.contextoDelServidor())) {
            Map<String, Entrega> resultado = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "",
                    "avisos@example.com", 465, null)
                    .enviar(List.of(new CorreoDirigido("usuario@example.com", CORREO)));

            assertThat(resultado.get("usuario@example.com").enviada()).isFalse();
            assertThat(smtp.mensajes()).isEmpty();
        }
    }

    @Test
    @DisplayName("a 4xx answer to a recipient is transient, so the outbox backs off instead of giving up")
    void unCuatroXXEsTransitorio() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            smtp.rechazarTemporalmente("ocupado@example.com");

            Map<String, Entrega> resultado = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "", "avisos@example.com")
                    .enviar(List.of(new CorreoDirigido("ocupado@example.com", CORREO),
                            new CorreoDirigido("uno@example.com", CORREO)));

            assertThat(resultado.get("ocupado@example.com").enviada()).isFalse();
            assertThat(resultado.get("ocupado@example.com").permanente()).isFalse();
            assertThat(resultado.get("uno@example.com").enviada()).isTrue();
        }
    }

    @Test
    @DisplayName("an unreachable server is transient for every recipient")
    void sinServidorEsTransitorio() throws Exception {
        int puertoCerrado;
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            puertoCerrado = smtp.puerto();
        }

        Map<String, Entrega> resultado = new SmtpMailAdapter("127.0.0.1", puertoCerrado, "", "", "avisos@example.com")
                .enviar(List.of(new CorreoDirigido("uno@example.com", CORREO)));

        assertThat(resultado.get("uno@example.com").permanente()).isFalse();
    }

    @Test
    @DisplayName("the Message-ID is derived from the claim key and the sender's domain, so a resend is the same message")
    void messageIdDeterministico() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            SmtpMailAdapter adapter = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "", "avisos@example.com");
            adapter.enviar(List.of(new CorreoDirigido("uno@example.com", CORREO, "abc123")));
            adapter.enviar(List.of(new CorreoDirigido("uno@example.com", CORREO, "abc123")));
            adapter.enviar(List.of(new CorreoDirigido("uno@example.com", CORREO, "def456")));
            adapter.enviar(List.of(new CorreoDirigido("uno@example.com", CORREO)));

            assertThat(smtp.mensajes()).hasSize(4);
            assertThat(parsear(smtp.mensajes().get(0)).getMessageID()).isEqualTo("<notif-abc123@example.com>");
            assertThat(parsear(smtp.mensajes().get(1)).getMessageID()).isEqualTo("<notif-abc123@example.com>");
            assertThat(parsear(smtp.mensajes().get(2)).getMessageID()).isEqualTo("<notif-def456@example.com>");
            assertThat(parsear(smtp.mensajes().get(3)).getMessageID()).isNotNull().doesNotContain("notif-");
        }
    }

    @Test
    @DisplayName("RFC 8058: List-Unsubscribe carries the link and List-Unsubscribe-Post asks for one-click; absent without a link")
    void cabecerasDeBajaConUnClick() throws Exception {
        try (FakeSmtpServer smtp = new FakeSmtpServer()) {
            SmtpMailAdapter adapter = new SmtpMailAdapter("127.0.0.1", smtp.puerto(), "", "", "avisos@example.com");
            adapter.enviar(List.of(new CorreoDirigido("uno@example.com", CORREO, "abc123",
                    "https://api.example.com/api/notificaciones/baja/T.K")));
            adapter.enviar(List.of(new CorreoDirigido("uno@example.com", CORREO)));

            MimeMessage con = parsear(smtp.mensajes().get(0));
            MimeMessage sin = parsear(smtp.mensajes().get(1));
            assertThat(con.getHeader("List-Unsubscribe", null)).isEqualTo("<https://api.example.com/api/notificaciones/baja/T.K>");
            assertThat(con.getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");
            assertThat(sin.getHeader("List-Unsubscribe")).isNull();
            assertThat(sin.getHeader("List-Unsubscribe-Post")).isNull();
        }
    }

    private static MimeMessage parsear(byte[] bytes) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
    }

    /** Spring nests the alternative inside mixed/related wrappers; the part may be at any depth. */
    private static String contenidoDe(Part parte, String tipo) throws Exception {
        if (parte.isMimeType(tipo)) {
            return (String) parte.getContent();
        }
        if (parte.getContent() instanceof Multipart hijos) {
            for (int i = 0; i < hijos.getCount(); i++) {
                String encontrado = hijoOVacio(hijos.getBodyPart(i), tipo);
                if (encontrado != null) {
                    return encontrado;
                }
            }
        }
        throw new IOException("sin parte " + tipo);
    }

    private static String hijoOVacio(Part hijo, String tipo) throws Exception {
        try {
            return contenidoDe(hijo, tipo);
        } catch (IOException ausente) {
            return null;
        }
    }

    @Test
    @DisplayName("probarConexion: a listening server is OK and nothing is sent")
    void sondeoOk() throws Exception {
        try (FakeSmtpServer servidor = new FakeSmtpServer()) {
            SmtpMailAdapter adapter = new SmtpMailAdapter("127.0.0.1", servidor.puerto(), "", "", "avisos@example.com");

            EstadoSmtp e = adapter.probarConexion();

            assertThat(e.ok()).as(e.mensaje()).isTrue();
            assertThat(servidor.mensajes()).isEmpty();
        }
    }

    @Test
    @DisplayName("probarConexion: a closed port is a FALLO that names host:port")
    void sondeoSinServidor() throws Exception {
        int puerto;
        try (FakeSmtpServer servidor = new FakeSmtpServer()) {
            puerto = servidor.puerto();
        }

        EstadoSmtp e = new SmtpMailAdapter("127.0.0.1", puerto, "", "", "avisos@example.com").probarConexion();

        assertThat(e.nivel()).isEqualTo(EstadoSmtp.Nivel.FALLO);
        assertThat(e.mensaje()).contains("127.0.0.1:" + puerto);
    }

    @Test
    @DisplayName("probarConexion: rejected credentials point at the app password")
    void sondeoCredenciales() throws Exception {
        try (FakeSmtpServer servidor = new FakeSmtpServer()) {
            servidor.rechazarCredenciales();
            SmtpMailAdapter adapter = new SmtpMailAdapter("127.0.0.1", servidor.puerto(), "cuenta@example.com", "mala", "");

            EstadoSmtp e = adapter.probarConexion();

            assertThat(e.nivel()).isEqualTo(EstadoSmtp.Nivel.FALLO);
            assertThat(e.pista()).containsIgnoringCase("contraseña de aplicación");
            assertThat(e.mensaje() + e.pista()).doesNotContain("mala");
        }
    }
}
