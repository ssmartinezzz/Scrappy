package ar.scraper.notificaciones;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * One line at boot saying whether mail can leave. The connection test runs on its own thread
 * after the app is ready, so a slow or dead SMTP server can neither delay nor fail the boot.
 */
@Component
public class ChequeoSmtpAlArrancar {

    private static final Logger LOG = LoggerFactory.getLogger(ChequeoSmtpAlArrancar.class);

    private final DiagnosticoSmtp diagnostico;
    private final ConfiguracionSmtp configuracion;
    private final Executor ejecutor;

    @Autowired
    public ChequeoSmtpAlArrancar(DiagnosticoSmtp diagnostico,
                                 @Value("${smtp.host:}") String host,
                                 @Value("${smtp.port:587}") int port,
                                 @Value("${smtp.username:}") String username,
                                 @Value("${smtp.password:}") String password,
                                 @Value("${smtp.from-address:}") String from) {
        this(diagnostico, new ConfiguracionSmtp(host, port, username, password, from),
                tarea -> Thread.ofVirtual().name("smtp-chequeo").start(tarea));
    }

    ChequeoSmtpAlArrancar(DiagnosticoSmtp diagnostico, ConfiguracionSmtp configuracion, Executor ejecutor) {
        this.diagnostico = diagnostico;
        this.configuracion = configuracion;
        this.ejecutor = ejecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (StringUtils.isBlank(configuracion.host())) {
            return;
        }
        var problema = configuracion.problema();
        if (problema.isPresent()) {
            LOG.warn("[SMTP] configuración incompleta: {}", problema.get());
            return;
        }
        ejecutor.execute(this::sondear);
    }

    private void sondear() {
        try {
            EstadoSmtp estado = diagnostico.probar();
            if (estado.ok()) {
                LOG.info("[SMTP] OK: {} acepta conexiones, los avisos de baja de precio van a salir por acá.",
                        configuracion.destino());
            } else {
                LOG.warn("[SMTP] {} ({}). {}", estado.mensaje(), configuracion.destino(), estado.pista());
            }
        } catch (RuntimeException e) {
            LOG.warn("[SMTP] no se pudo verificar {}: {}", configuracion.destino(), e.getClass().getSimpleName());
        }
    }
}
