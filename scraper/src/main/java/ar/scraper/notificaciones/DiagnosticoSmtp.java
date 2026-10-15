package ar.scraper.notificaciones;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DiagnosticoSmtp {

    private static final String CUERPO = "Si estás leyendo esto, el envío de mails de Scrappy funciona.";

    private final MailPort mail;

    public DiagnosticoSmtp(MailPort mail) {
        this.mail = mail;
    }

    public EstadoSmtp probar() {
        return mail.probarConexion();
    }

    /** Checks the connection first so a failure names its cause instead of a generic send error. */
    public EstadoSmtp enviarPrueba(String destino) {
        EstadoSmtp conexion = probar();
        if (!conexion.ok()) {
            return conexion;
        }
        CorreoDirigido correo = new CorreoDirigido(destino, new CorreoRenderizado(
                "Mail de prueba de Scrappy", "<p>" + CUERPO + "</p>", CUERPO));
        Entrega entrega;
        try {
            entrega = mail.enviar(List.of(correo)).get(destino);
        } catch (RuntimeException e) {
            return EstadoSmtp.fallo(e.getClass().getSimpleName() + ": " + ErroresSmtp.sinDirecciones(String.valueOf(e.getMessage())),
                    "Revisá el log del backend.");
        }
        if (entrega == null || !entrega.enviada()) {
            String motivo = entrega == null ? "el servidor no devolvió resultado" : entrega.error();
            return EstadoSmtp.fallo(ErroresSmtp.sinDirecciones(motivo), "Revisá la dirección de destino y el log del backend.");
        }
        return EstadoSmtp.ok("Mail de prueba enviado a " + Enmascarar.direccion(destino) + ".");
    }
}
