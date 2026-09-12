package ar.scraper.scrape;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Capability port for the {@code scrape_run}/{@code scrape_run_sitio} aggregate:
 * the lifecycle of one scraping run, site by site, plus the interrupted-run
 * bookkeeping that lets a killed process be resumed
 * (extract-ml-persistence-ports, port 9 of 13).
 *
 * <p>It lives in {@code scrape} because {@link CorridaInterrumpida}, the type
 * it returns, already does.</p>
 *
 * <p>Method names are the repository's, not the facade's — decision D3 of
 * extract-catalog-query-port. {@code DatabaseService} keeps spelling them
 * {@code crearScrapeRun}/{@code finalizarScrapeRun}/{@code reabrirScrapeRun}
 * and renames while delegating.</p>
 */
public interface ScrapeRunPort {

    /** Opens a run and returns its id. */
    long crear(UUID scrapeUuid, Instant startedAt, UUID triggeredBy, Long cronJobId,
               Collection<String> sitios) throws SQLException;

    void marcarSitioEnCurso(long runId, String sitio, Instant cuando) throws SQLException;

    void marcarSitioTerminado(long runId, String sitio, String status, int productosCount,
                              String error, Instant cuando) throws SQLException;

    void finalizar(long runId, String status, int productosCount, Instant finishedAt)
            throws SQLException;

    /** Boot-time sweep: any run still open belonged to a process that died. */
    List<Long> marcarInterrumpidosAlArrancar(Instant cuando) throws SQLException;

    Optional<CorridaInterrumpida> ultimaInterrumpida() throws SQLException;

    void reabrir(long runId) throws SQLException;

    List<String> marcarAusentesDelRegistro(long runId, Collection<String> nombresActuales)
            throws SQLException;

    boolean existeCorridaCompletada() throws SQLException;

    Optional<Instant> startedAtDe(long runId) throws SQLException;
}
