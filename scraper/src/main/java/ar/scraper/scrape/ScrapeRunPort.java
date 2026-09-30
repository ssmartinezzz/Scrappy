package ar.scraper.scrape;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code DatabaseService} keeps spelling them
 * {@code crearScrapeRun}/{@code finalizarScrapeRun}/{@code reabrirScrapeRun} and renames while
 * delegating.
 */
public interface ScrapeRunPort {

    long crear(UUID scrapeUuid, Instant startedAt, UUID triggeredBy, Long cronJobId,
               Collection<String> sitios);

    void marcarSitioEnCurso(long runId, String sitio, Instant cuando);

    void marcarSitioTerminado(long runId, String sitio, String status, int productosCount,
                              String error, Instant cuando);

    void finalizar(long runId, String status, int productosCount, Instant finishedAt);

    List<Long> marcarInterrumpidosAlArrancar(Instant cuando);

    Optional<CorridaInterrumpida> ultimaInterrumpida();

    void reabrir(long runId);

    /**
     * Todas, no la ofrecida: {@link #ultimaInterrumpida} nombra solo la mas reciente, asi que de a
     * una destaparia la siguiente en el proximo arranque.
     */
    List<Long> descartarInterrumpidas(Instant cuando);

    List<String> marcarAusentesDelRegistro(long runId, Collection<String> nombresActuales);

    boolean existeCorridaCompletada();

    Optional<Instant> startedAtDe(long runId);
}
