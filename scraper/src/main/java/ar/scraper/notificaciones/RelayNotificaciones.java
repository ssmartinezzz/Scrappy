package ar.scraper.notificaciones;

import ar.scraper.api.LogSafe;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;

/**
 * Drains the price-drop outbox. Delivery is at-least-once: a crash after a mail left and before
 * its rows were marked sends it again once the lease expires.
 */
@Component
@ConditionalOnProperty(name = "notificaciones.enabled", havingValue = "true", matchIfMissing = true)
public class RelayNotificaciones implements NovedadesEnLaCola {

    private static final Logger LOG = LoggerFactory.getLogger(RelayNotificaciones.class);

    private final NotificacionOutboxPort outbox;
    private final NotificacionDespachador despachador;
    private final RelayConfig config;
    private final Clock reloj;
    private volatile Instant proximaPurga;

    private final Executor ejecutor;
    private final Executor purgador;
    private final List<ExecutorService> propios = new ArrayList<>();
    private final AtomicBoolean purgando = new AtomicBoolean();
    private final AtomicBoolean despertado = new AtomicBoolean();

    @Autowired
    public RelayNotificaciones(NotificacionOutboxPort outbox, NotificacionDespachador despachador,
                               RelayConfig config, Clock reloj) {
        this(outbox, despachador, config, reloj, hiloPropio("notificaciones-relay"), hiloPropio("notificaciones-purga"));
    }

    private static ExecutorService hiloPropio(String nombre) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, nombre);
            t.setDaemon(true);
            return t;
        });
    }

    RelayNotificaciones(NotificacionOutboxPort outbox, NotificacionDespachador despachador,
                        RelayConfig config, Clock reloj, Executor ejecutor) {
        this(outbox, despachador, config, reloj, ejecutor, ejecutor);
    }

    RelayNotificaciones(NotificacionOutboxPort outbox, NotificacionDespachador despachador,
                        RelayConfig config, Clock reloj, Executor ejecutor, Executor purgador) {
        this.outbox = outbox;
        this.despachador = despachador;
        this.config = config;
        this.reloj = reloj;
        this.ejecutor = ejecutor;
        this.purgador = purgador;
        for (Executor e : new Executor[]{ejecutor, purgador}) {
            if (e instanceof ExecutorService es && !propios.contains(es)) {
                propios.add(es);
            }
        }
    }

    /**
     * Called from the database listener thread, which must never do the work itself. The flag is
     * cleared before the run starts, so a signal that lands mid-run schedules one more run rather
     * than being swallowed.
     */
    @Override
    public void hayNovedades() {
        if (despertado.compareAndSet(false, true)) {
            ejecutor.execute(() -> {
                despertado.set(false);
                tick();
            });
        }
    }

    @PreDestroy
    void cerrar() {
        propios.forEach(ExecutorService::shutdownNow);
    }

    @Scheduled(fixedDelayString = "${notificaciones.intervalo-ms:15000}",
               initialDelayString = "${notificaciones.intervalo-ms:15000}")
    public synchronized void tick() {
        try {
            while (procesarLote() == config.lote()) {
                LOG.debug("[NOTIF] lote completo, sigo drenando");
            }
        } catch (RuntimeException e) {
            LOG.warn("[NOTIF] no se pudo procesar el lote: {}", LogSafe.of(ErroresSmtp.sinDirecciones(e.toString())));
        }
    }

    /**
     * The purge is slow (minutes on a large backlog) and must never sit in front of delivery: it runs on
     * its own thread and takes no relay lock. The scheduler pool has one thread by default, which is why
     * this method only hands the work off. The flag keeps two purges from overlapping.
     */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void purgar() {
        if (!purgando.compareAndSet(false, true)) {
            return;
        }
        try {
            purgador.execute(() -> {
                try {
                    purgarSiCorresponde();
                } finally {
                    purgando.set(false);
                }
            });
        } catch (RuntimeException e) {
            purgando.set(false);
            throw e;
        }
    }

    /**
     * Housekeeping lives with the relay, the outbox's only consumer, rather than in the catalog
     * upsert: it must not run inside a scrape transaction nor depend on a scrape happening.
     */
    private void purgarSiCorresponde() {
        Instant ahora = reloj.instant();
        if (proximaPurga != null && ahora.isBefore(proximaPurga)) {
            return;
        }
        proximaPurga = ahora.plus(config.cadaPurga());
        try {
            int borradas = outbox.purgarTerminadas(config.retencion());
            if (borradas > 0) {
                LOG.info("[NOTIF] {} avisos terminados purgados (retención {} días)", borradas,
                        config.retencion().toDays());
            }
        } catch (RuntimeException e) {
            LOG.warn("[NOTIF] no se pudo purgar el outbox: {}", e.toString());
        }
    }

    /** Returns how many recipients were claimed. */
    public synchronized int procesarLote() {
        List<BajaReclamada> reclamadas = outbox.reclamar(config.lote(), config.maxIntentos(), config.lease(),
                config.maxPorMinuto());
        if (reclamadas.isEmpty()) {
            return 0;
        }
        Map<String, List<BajaReclamada>> porDestinatario = new LinkedHashMap<>();
        for (BajaReclamada baja : reclamadas) {
            porDestinatario.computeIfAbsent(baja.email(), e -> new ArrayList<>()).add(baja);
        }
        Map<String, Entrega> resultado = despacharTodos(porDestinatario);
        List<Long> idsEnviados = new ArrayList<>();
        List<FalloDeEnvio> fallos = new ArrayList<>();
        for (Map.Entry<String, List<BajaReclamada>> grupo : porDestinatario.entrySet()) {
            String email = grupo.getKey();
            List<Long> ids = grupo.getValue().stream().map(BajaReclamada::id).toList();
            Entrega entrega = resultado.getOrDefault(email, Entrega.fallo("el despachador no informó resultado"));
            if (entrega.enviada()) {
                idsEnviados.addAll(ids);
                continue;
            }
            String error = ErroresSmtp.sinDirecciones(StringUtils.defaultString(entrega.error()));
            LOG.warn("[NOTIF] fallo al avisar a {}: {}", LogSafe.of(Enmascarar.direccion(email)), LogSafe.of(error));
            for (Long id : ids) fallos.add(new FalloDeEnvio(id, error, entrega.permanente()));
        }
        outbox.marcarEnviadas(idsEnviados);
        outbox.registrarFallos(fallos, config.maxIntentos(), config.backoffBase());
        LOG.info("[NOTIF] {} bajas enviadas, {} con error", idsEnviados.size(), fallos.size());
        return porDestinatario.size();
    }

    /** An exception that escapes the dispatcher means nothing in the batch can be vouched for. */
    private Map<String, Entrega> despacharTodos(Map<String, List<BajaReclamada>> porDestinatario) {
        try {
            return despachador.despachar(porDestinatario);
        } catch (RuntimeException e) {
            Entrega fallo = Entrega.fallo(e.getClass().getSimpleName() + ": " + StringUtils.defaultString(e.getMessage()));
            Map<String, Entrega> todos = new LinkedHashMap<>();
            porDestinatario.keySet().forEach(email -> todos.put(email, fallo));
            return todos;
        }
    }
}
