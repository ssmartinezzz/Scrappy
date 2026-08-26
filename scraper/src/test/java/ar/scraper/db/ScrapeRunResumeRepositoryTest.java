package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 5 (tasks 5.1, 5.4) — reading and
 * reopening the run a dead process left behind.
 *
 * <p>The load-bearing property is that a resume <b>reuses the original run
 * row</b> instead of opening a new one. That is what keeps the reader bound
 * honest across the interruption: {@code started_at} is preserved, so readers
 * stay isolated from <i>both</i> halves rather than only the second, and the
 * final soft-delete pass — which derives its scope from
 * {@code touched_at >= started_at} — spans the whole run without anyone
 * widening a site list by hand. Opening a second run would need a
 * {@code resumed_from} column and would make the bound name only half the
 * work.</p>
 */
@Epic("Persistence")
@Feature("Resume")
@Story("Reading the interrupted run and reopening it in place")
@DisplayName("ScrapeRunRepository — interrupted run and resume")
class ScrapeRunResumeRepositoryTest extends PostgresTestBase {

    private ScrapeRunRepository repo() {
        return new ScrapeRunRepository(dataSource());
    }

    @Test
    @DisplayName("the interrupted run is found with its sites split into done and pending")
    void findsTheInterruptedRunAndSplitsItsSites() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres", "midway", "batuk", "tussy"));
        repo().marcarSitioTerminado(runId, "freres", "DONE", 120, null, Instant.now());
        repo().marcarSitioTerminado(runId, "midway", "ERROR", 0, "timeout", Instant.now());
        repo().marcarSitioEnCurso(runId, "batuk", Instant.now());   // moría acá
        // tussy queda PENDING: nunca le tocó el turno

        repo().marcarInterrumpidosAlArrancar(Instant.now());

        var interrumpida = repo().ultimaInterrumpida().orElseThrow();
        assertThat(interrumpida.runId()).isEqualTo(runId);
        assertThat(interrumpida.atendidos())
                .as("DONE y ERROR ya tuvieron su turno; ERROR además reintentó "
                    + "tres veces adentro del run, así que repetirlo es repetir un fallo")
                .containsExactlyInAnyOrder("freres", "midway");
        assertThat(interrumpida.pendientes())
                .as("el que estaba EN CURSO cuando murió el proceso es tan pendiente "
                    + "como el que nunca arrancó — su resultado se perdió entero")
                .containsExactlyInAnyOrder("batuk", "tussy");
    }

    @Test
    @DisplayName("a run interrupted after every site finished has nothing pending")
    void aRunInterruptedDuringAggregationHasNothingPending() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres", "midway"));
        repo().marcarSitioTerminado(runId, "freres", "DONE", 10, null, Instant.now());
        repo().marcarSitioTerminado(runId, "midway", "DONE", 20, null, Instant.now());

        repo().marcarInterrumpidosAlArrancar(Instant.now());

        // The case that gets forgotten: the crash landed in the ML/aggregation
        // pass, after every site was already scraped. Re-scraping here is pure
        // wasted work — the resume owes only the final pass.
        assertThat(repo().ultimaInterrumpida().orElseThrow().pendientes()).isEmpty();
    }

    @Test
    @DisplayName("reopening preserves started_at — that is the whole point")
    void reopeningPreservesStartedAt() throws Exception {
        Instant arranque = Instant.parse("2026-08-25T10:00:00Z");
        long runId = repo().crear(UUID.randomUUID(), arranque, null, null, List.of("freres"));
        repo().marcarInterrumpidosAlArrancar(Instant.now());

        repo().reabrir(runId);

        assertThat(repo().startedAtDe(runId))
                .as("a new run row would make the reader bound name only the second "
                    + "half, and the final sweep would miss the first")
                .contains(arranque.truncatedTo(ChronoUnit.SECONDS));
        assertThat(estadoDelRun(runId)).isEqualTo("RUNNING");
        assertThat(finishedAtDe(runId))
                .as("RUNNING and finished_at cannot disagree — the paired CHECK")
                .isNull();
    }

    @Test
    @DisplayName("reopening puts a site caught mid-scrape back to PENDING")
    void reopeningResetsTheSiteThatWasRunning() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres", "batuk"));
        repo().marcarSitioTerminado(runId, "freres", "DONE", 5, null, Instant.now());
        repo().marcarSitioEnCurso(runId, "batuk", Instant.now());
        repo().marcarInterrumpidosAlArrancar(Instant.now());

        repo().reabrir(runId);

        assertThat(sitiosConEstado(runId, "PENDING")).containsExactly("batuk");
        assertThat(sitiosConEstado(runId, "DONE"))
                .as("lo ya scrapeado no se vuelve a pedir")
                .containsExactly("freres");
    }

    @Test
    @DisplayName("a site that left the registry between crash and restart is SKIPPED, not lost")
    void aSiteRemovedFromTheRegistryIsSkipped() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres", "midway"));
        repo().marcarInterrumpidosAlArrancar(Instant.now());

        assertThat(repo().marcarAusentesDelRegistro(runId, List.of("freres")))
                .as("devuelve cuáles fueron, para poder nombrarlos")
                .containsExactly("midway");

        assertThat(sitiosConEstado(runId, "SKIPPED")).containsExactly("midway");
        assertThat(repo().ultimaInterrumpida().orElseThrow().pendientes())
                .as("no se ofrece retomar un sitio que ya no existe, y el operador "
                    + "tiene que enterarse de cuál fue en vez de que desaparezca")
                .containsExactly("freres");
    }

    @Test
    @DisplayName("the registry comparison normalizes, so a site with punctuation is not dropped")
    void theRegistryComparisonNormalizes() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of("Full H4rd"));
        repo().marcarInterrumpidosAlArrancar(Instant.now());

        // El registro devuelve el nombre para mostrar, no la clave. Si la
        // comparación no normalizara, "Full H4rd" no matchearía con la clave
        // guardada `fullh4rd`, el sitio se marcaría SKIPPED y desaparecería en
        // silencio de una retoma que lo debía.
        assertThat(repo().marcarAusentesDelRegistro(runId, List.of("Full H4rd")))
                .as("mismo sitio, misma clave: no falta del registro")
                .isEmpty();
        assertThat(repo().ultimaInterrumpida().orElseThrow().pendientes())
                .containsExactly("fullh4rd");
    }

    @Test
    @DisplayName("a completed run is not offered for resume")
    void aCompletedRunIsNotOffered() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null, List.of("freres"));
        repo().finalizar(runId, "COMPLETED", 100, Instant.now());

        repo().marcarInterrumpidosAlArrancar(Instant.now());

        assertThat(repo().ultimaInterrumpida()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String estadoDelRun(long runId) throws Exception {
        return unString("SELECT status FROM scrape_run WHERE id = " + runId);
    }

    private String finishedAtDe(long runId) throws Exception {
        return unString("SELECT finished_at::text FROM scrape_run WHERE id = " + runId);
    }

    private List<String> sitiosConEstado(long runId, String estado) throws Exception {
        return consultar("""
            SELECT sitio_key FROM scrape_run_site
            WHERE scrape_run_id = %d AND status = '%s' ORDER BY sitio_key
            """.formatted(runId, estado));
    }

    private String unString(String sql) throws Exception {
        List<String> filas = consultar(sql);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private List<String> consultar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new java.util.ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }
}
