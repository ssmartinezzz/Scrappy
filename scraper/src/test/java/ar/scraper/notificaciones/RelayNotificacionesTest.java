package ar.scraper.notificaciones;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelayNotificaciones — one mail per recipient, mark or back off per recipient")
class RelayNotificacionesTest {

    private static final UUID USUARIO = UUID.randomUUID();
    private static final UUID OTRO_USUARIO = UUID.randomUUID();

    private final List<BajaReclamada> pendientes = new ArrayList<>();
    private final List<Long> enviadas = new ArrayList<>();
    private final List<Long> falladas = new ArrayList<>();
    private final List<String> errores = new ArrayList<>();
    private final List<Long> permanentes = new ArrayList<>();
    private final List<List<String>> lotes = new ArrayList<>();
    private String destinoQueFalla;
    private int reclamos;
    private final List<Duration> purgas = new ArrayList<>();
    private final List<Integer> limitesPedidos = new ArrayList<>();
    private final List<Integer> topesPedidos = new ArrayList<>();
    private int llamadasAEnviadas;
    private int llamadasAFallos;

    private final NotificacionOutboxPort outbox = new NotificacionOutboxPort() {
        @Override
        public List<BajaReclamada> reclamar(int limite, int maxIntentos, Duration lease, int tope) {
            topesPedidos.add(tope);
            List<String> destinatarios = pendientes.stream().map(BajaReclamada::email).distinct().limit(limite).toList();
            List<BajaReclamada> lote = pendientes.stream().filter(b -> destinatarios.contains(b.email())).toList();
            pendientes.removeAll(lote);
            reclamos++;
            limitesPedidos.add(limite);
            return lote;
        }

        @Override
        public int purgarTerminadas(Duration retencion) {
            purgas.add(retencion);
            return 0;
        }

        @Override
        public void marcarEnviadas(Collection<Long> ids) {
            llamadasAEnviadas++;
            enviadas.addAll(ids);
        }

        @Override
        public void registrarFallos(Collection<FalloDeEnvio> fallos, int maxIntentos, Duration backoffBase) {
            llamadasAFallos++;
            fallos.forEach(f -> {
                falladas.add(f.id());
                errores.add(f.error());
                if (f.permanente()) permanentes.add(f.id());
            });
        }
    };

    private final NotificacionDespachador despachador = envios -> {
        lotes.add(envios.keySet().stream().map(e -> e + ":" + envios.get(e).size()).toList());
        Map<String, Entrega> resultado = new LinkedHashMap<>();
        envios.keySet().forEach(e -> resultado.put(e, e.equals(destinoQueFalla)
                ? Entrega.fallo("smtp rechazó " + e) : Entrega.ok()));
        return resultado;
    };

    private final RelojDePrueba reloj = new RelojDePrueba();
    private RelayNotificaciones relay;

    private RelayNotificaciones nuevoRelay(NotificacionDespachador d, RelayConfig config) {
        return new RelayNotificaciones(outbox, d, config, reloj, Runnable::run);
    }

    @BeforeEach
    void setUp() {
        relay = nuevoRelay(despachador, RelayConfig.porDefecto());
    }

    private static BajaReclamada baja(long id, UUID usuario, String email) {
        return new BajaReclamada(id, usuario, email, "https://s.test/" + id, "P" + id, "Sitio", 100, 80);
    }

    @Test
    @DisplayName("the whole tick goes to the dispatcher in ONE call, grouped by recipient, and sent rows are marked together")
    void unDespachoPorLote() {
        pendientes.addAll(List.of(baja(1, USUARIO, "usuario@example.com"),
                baja(2, OTRO_USUARIO, "otro@example.com"), baja(3, USUARIO, "usuario@example.com")));

        int destinatarios = relay.procesarLote();

        assertThat(lotes).containsExactly(List.of("usuario@example.com:2", "otro@example.com:1"));
        assertThat(enviadas).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(falladas).isEmpty();
        assertThat(destinatarios).isEqualTo(2);
    }

    @Test
    @DisplayName("one recipient's failure backs off only their rows and does not stop the others")
    void unaFallaNoDetieneALasDemas() {
        destinoQueFalla = "usuario@example.com";
        pendientes.addAll(List.of(baja(1, USUARIO, "usuario@example.com"), baja(2, OTRO_USUARIO, "otro@example.com")));

        relay.procesarLote();

        assertThat(falladas).containsExactly(1L);
        assertThat(errores).singleElement().asString().contains("smtp rechazó");
        assertThat(enviadas).containsExactly(2L);
    }

    @Test
    @DisplayName("sent rows are marked with one call and failures recorded with one call per batch, however many recipients")
    void unaLlamadaPorTipoDeResultado() {
        relay = nuevoRelay(envios -> {
            Map<String, Entrega> r = new LinkedHashMap<>();
            envios.keySet().forEach(e -> r.put(e, e.startsWith("falla")
                    ? Entrega.fallo("rechazo de " + e) : Entrega.ok()));
            return r;
        }, RelayConfig.porDefecto());
        pendientes.addAll(List.of(baja(1, USUARIO, "ok1@example.com"), baja(2, OTRO_USUARIO, "falla1@example.com"),
                baja(3, UUID.randomUUID(), "ok2@example.com"), baja(4, UUID.randomUUID(), "falla2@example.com")));

        relay.procesarLote();

        assertThat(llamadasAEnviadas).isEqualTo(1);
        assertThat(llamadasAFallos).isEqualTo(1);
        assertThat(enviadas).containsExactlyInAnyOrder(1L, 3L);
        assertThat(falladas).containsExactlyInAnyOrder(2L, 4L);
        assertThat(errores).hasSize(2).noneMatch(e -> e.contains("falla1@example.com") || e.contains("falla2@example.com"));
    }

    @Test
    @DisplayName("the recorded error never carries the full address")
    void elErrorNoLlevaLaDireccion() {
        destinoQueFalla = "usuario@example.com";
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.procesarLote();

        assertThat(errores).singleElement().asString().doesNotContain("usuario@example.com");
    }

    @Test
    @DisplayName("a provider that echoes the address in another case still does not leak it into the stored error")
    void elErrorNoLlevaLaDireccionEnOtraCapitalizacion() {
        relay = nuevoRelay(envios -> {
            Map<String, Entrega> r = new LinkedHashMap<>();
            envios.keySet().forEach(e -> r.put(e, Entrega.rechazo("550 5.1.1 <" + e.toUpperCase() + "> unknown")));
            return r;
        }, RelayConfig.porDefecto());
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.procesarLote();

        assertThat(errores).singleElement().asString().doesNotContainIgnoringCase("usuario@example.com");
    }

    @Test
    @DisplayName("a dispatcher that blows up for the whole batch backs off every recipient instead of losing the claim")
    void despachadorQueExplota() {
        relay = nuevoRelay(envios -> {
            throw new IllegalStateException("kafka caído");
        }, RelayConfig.porDefecto());
        pendientes.addAll(List.of(baja(1, USUARIO, "usuario@example.com"), baja(2, OTRO_USUARIO, "otro@example.com")));

        relay.procesarLote();

        assertThat(falladas).containsExactlyInAnyOrder(1L, 2L);
        assertThat(enviadas).isEmpty();
    }

    @Test
    @DisplayName("a recipient the dispatcher says nothing about counts as failed, never as sent")
    void destinatarioSinResultado() {
        relay = nuevoRelay(envios -> Map.of(), RelayConfig.porDefecto());
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.procesarLote();

        assertThat(falladas).containsExactly(1L);
        assertThat(enviadas).isEmpty();
    }

    @Test
    @DisplayName("a tick keeps claiming while batches come back full, so a burst is not paced by the poll interval")
    void unTickDrenaLaRafaga() {
        relay = nuevoRelay(despachador, RelayConfig.porDefecto().conLote(2));
        for (long id = 1; id <= 5; id++) {
            pendientes.add(baja(id, UUID.randomUUID(), "usuario" + id + "@example.com"));
        }

        relay.tick();

        assertThat(enviadas).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(reclamos).isEqualTo(3);
    }

    @Test
    @DisplayName("the drain loop counts recipients: a user with many rows does not look like a full batch")
    void elLoteSeCuentaEnDestinatarios() {
        relay = nuevoRelay(despachador, RelayConfig.porDefecto().conLote(2));
        for (long id = 1; id <= 6; id++) pendientes.add(baja(id, USUARIO, "usuario@example.com"));

        relay.tick();

        assertThat(reclamos).as("2 recipients per batch allowed, 1 present: no second claim").isEqualTo(1);
        assertThat(enviadas).hasSize(6);
    }

    @Test
    @DisplayName("nothing due means nothing dispatched")
    void sinPendientes() {
        assertThat(relay.procesarLote()).isZero();
        assertThat(lotes).isEmpty();
    }

    private void encolarDestinatarios(int cuantos) {
        for (int i = 0; i < cuantos; i++) {
            pendientes.add(baja(1000L + i, UUID.randomUUID(), "usuario" + i + "@example.com"));
        }
    }

    @Test
    @DisplayName("the cap is handed to the outbox, which enforces it for the whole cluster; the relay still asks for a full batch")
    void elTopeViajaAlOutbox() {
        relay = nuevoRelay(despachador, RelayConfig.porDefecto().conMaxPorMinuto(3));
        encolarDestinatarios(5);

        relay.procesarLote();

        assertThat(topesPedidos).containsExactly(3);
        assertThat(limitesPedidos).containsExactly(200);
    }

    @Test
    @DisplayName("zero means no cap")
    void sinTope() {
        relay = nuevoRelay(despachador, RelayConfig.porDefecto().conMaxPorMinuto(0).conLote(50));
        encolarDestinatarios(7);

        assertThat(relay.procesarLote()).isEqualTo(7);
        assertThat(limitesPedidos).containsExactly(50);
        assertThat(topesPedidos).containsExactly(0);
    }

    @Test
    @DisplayName("a wake-up drains the queue right away instead of waiting for the next poll")
    void despertarDrena() {
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.hayNovedades();

        assertThat(enviadas).containsExactly(1L);
    }

    @Test
    @DisplayName("a burst of wake-ups queues a single run; one that arrives after the run started queues another")
    void losDespertaresSeAgrupan() {
        List<Runnable> cola = new ArrayList<>();
        relay = new RelayNotificaciones(outbox, despachador, RelayConfig.porDefecto(), reloj, cola::add);
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.hayNovedades();
        relay.hayNovedades();
        relay.hayNovedades();
        assertThat(cola).hasSize(1);

        Runnable corrida = cola.removeFirst();
        pendientes.add(baja(2, OTRO_USUARIO, "otro@example.com"));
        corrida.run();
        assertThat(enviadas).containsExactlyInAnyOrder(1L, 2L);

        relay.hayNovedades();
        assertThat(cola).hasSize(1);
    }

    @Test
    @DisplayName("a wake-up that fails inside the run never escapes into the notifier thread")
    void despertarNoPropagaErrores() {
        relay = nuevoRelay(envios -> {
            throw new IllegalStateException("caído");
        }, RelayConfig.porDefecto());
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.hayNovedades();

        assertThat(falladas).containsExactly(1L);
    }

    @Test
    @DisplayName("the purge removes finished rows with the configured retention, at most once per interval")
    void purgaAcotadaEnElTiempo() {
        RelayConfig config = RelayConfig.porDefecto();
        relay = nuevoRelay(despachador, config);

        relay.purgar();
        relay.purgar();
        assertThat(purgas).as("second call is inside the interval").containsExactly(config.retencion());

        reloj.avanzar(config.cadaPurga().minusSeconds(1));
        relay.purgar();
        assertThat(purgas).hasSize(1);

        reloj.avanzar(Duration.ofSeconds(2));
        relay.purgar();
        assertThat(purgas).hasSize(2);
    }

    @Test
    @DisplayName("a tick delivers and never purges: the purge has its own schedule")
    void elTickNoPurga() {
        relay = nuevoRelay(despachador, RelayConfig.porDefecto());
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.tick();

        assertThat(enviadas).containsExactly(1L);
        assertThat(purgas).isEmpty();
    }

    @Test
    @DisplayName("a purge that fails is logged and never stops the drain")
    void laPurgaQueFallaNoDetieneElEnvio() {
        relay = new RelayNotificaciones(outboxConPurga(retencion -> {
            throw new IllegalStateException("db caída");
        }), despachador, RelayConfig.porDefecto(), reloj, Runnable::run);
        pendientes.add(baja(1, USUARIO, "usuario@example.com"));

        relay.purgar();
        relay.tick();

        assertThat(enviadas).containsExactly(1L);
    }

    @Test
    @DisplayName("a purge stuck mid-way does not hold the delivery lock: tick() still completes")
    void unaPurgaBloqueadaNoFrenaLaEntrega() throws Exception {
        CountDownLatch empezo = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService purgador = Executors.newSingleThreadExecutor();
        try {
            relay = new RelayNotificaciones(outboxConPurga(retencion -> {
                empezo.countDown();
                await(soltar);
                return 0;
            }), despachador, RelayConfig.porDefecto(), reloj, Runnable::run, purgador);
            pendientes.add(baja(1, USUARIO, "usuario@example.com"));

            relay.purgar();
            assertThat(empezo.await(2, TimeUnit.SECONDS)).as("purge started").isTrue();

            Future<?> entrega = Executors.newSingleThreadExecutor().submit(relay::tick);
            entrega.get(2, TimeUnit.SECONDS);
            assertThat(enviadas).containsExactly(1L);
        } finally {
            soltar.countDown();
            purgador.shutdownNow();
        }
    }

    @Test
    @DisplayName("scheduling a purge while one is running does not start a second one")
    void dosPurgasNoSeSolapan() throws Exception {
        CountDownLatch empezo = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        AtomicInteger entradas = new AtomicInteger();
        ExecutorService purgador = Executors.newFixedThreadPool(2);
        try {
            relay = new RelayNotificaciones(outboxConPurga(retencion -> {
                entradas.incrementAndGet();
                empezo.countDown();
                await(soltar);
                return 0;
            }), despachador, RelayConfig.porDefecto(), reloj, Runnable::run, purgador);

            relay.purgar();
            assertThat(empezo.await(2, TimeUnit.SECONDS)).isTrue();
            reloj.avanzar(RelayConfig.porDefecto().cadaPurga().plusSeconds(1));
            relay.purgar();
            purgador.shutdown();
            soltar.countDown();
            assertThat(purgador.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

            assertThat(entradas).hasValue(1);
        } finally {
            soltar.countDown();
            purgador.shutdownNow();
        }
    }

    private NotificacionOutboxPort outboxConPurga(java.util.function.ToIntFunction<Duration> purga) {
        return new NotificacionOutboxPort() {
            @Override
            public List<BajaReclamada> reclamar(int m, int i, Duration l, int t) {
                return outbox.reclamar(m, i, l, t);
            }

            @Override
            public void marcarEnviadas(Collection<Long> ids) {
                outbox.marcarEnviadas(ids);
            }

            @Override
            public void registrarFallos(Collection<FalloDeEnvio> f, int m, Duration b) {
                outbox.registrarFallos(f, m, b);
            }

            @Override
            public int purgarTerminadas(Duration retencion) {
                return purga.applyAsInt(retencion);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a permanent rejection reaches the outbox flagged permanent; a transient one does not")
    void distingueRechazoPermanenteDeTransitorio() {
        relay = nuevoRelay(envios -> {
            Map<String, Entrega> r = new LinkedHashMap<>();
            envios.keySet().forEach(e -> r.put(e, e.startsWith("inexistente")
                    ? Entrega.rechazo("550 no such user") : Entrega.fallo("451 try later")));
            return r;
        }, RelayConfig.porDefecto());
        pendientes.addAll(List.of(baja(1, USUARIO, "inexistente@example.com"), baja(2, OTRO_USUARIO, "lento@example.com")));

        relay.procesarLote();

        assertThat(falladas).containsExactlyInAnyOrder(1L, 2L);
        assertThat(permanentes).containsExactly(1L);
    }
}
