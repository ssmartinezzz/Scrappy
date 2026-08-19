package ar.scraper.security;

import ar.scraper.security.ApiRoutePolicy.Access;
import ar.scraper.security.ApiRoutePolicy.RoutePolicy;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 7 — the policy table cannot shadow itself.
 *
 * <p>First match wins, so a rule placed above a more specific one silently takes
 * its place. Two shapes of that mistake matter here and they fail in <b>opposite
 * directions</b>, which is why they need different kinds of test.</p>
 *
 * <p>The {@code /api/ml/**} shape fails <b>closed</b>: a legitimate VIEWER gets
 * a 403 on a read. Annoying, visible, reported within a day. Ordering fixes it,
 * and an ordering test catches it.</p>
 *
 * <p>The {@code GET /api/db/export} shape fails <b>open</b>: a general
 * "GET means VIEWER" rule would make a full database export readable by every
 * account. Silent, and nobody reports it. <b>Ordering cannot fix that one</b> —
 * only never writing such a row can — so it is asserted as a structural property
 * rather than as an instance. A test that only checked {@code /api/db/export}
 * itself would pass while the same heuristic reappeared under a different
 * pattern.</p>
 */
@Epic("Security")
@Feature("Authorization matrix")
@Story("No rule shadows a later one, and the forbidden heuristic cannot come back")
@DisplayName("ApiRoutePolicy — shadowing")
class RoutePolicyShadowingTest {

    // ── case 1 · the carve-outs are actually consulted ───────────────────────

    @Test
    @DisplayName("GET /api/ml/estado and /resultado resolve to AUTHENTICATED, not ADMIN")
    void theMlReadsAreNotSwallowedByTheMlWildcard() {
        assertThat(ApiRoutePolicy.resolver(HttpMethod.GET, "/api/ml/estado"))
                .as("asserted on the RESULT, not on a row index — an index assertion passes "
                        + "happily when somebody reorders both rows together")
                .isEqualTo(Access.AUTHENTICATED);
        assertThat(ApiRoutePolicy.resolver(HttpMethod.GET, "/api/ml/resultado"))
                .isEqualTo(Access.AUTHENTICATED);
    }

    @Test
    @DisplayName("the ML mutations under the same prefix stay ADMIN")
    void theMlMutationsStayAdmin() {
        assertThat(ApiRoutePolicy.resolver(HttpMethod.POST, "/api/ml/entrenar")).isEqualTo(Access.ADMIN);
        assertThat(ApiRoutePolicy.resolver(HttpMethod.POST, "/api/ml/aplicar")).isEqualTo(Access.ADMIN);
        assertThat(ApiRoutePolicy.resolver(HttpMethod.POST, "/api/ml/renormalizar")).isEqualTo(Access.ADMIN);
    }

    // ── case 2 · the ADMIN read ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/db/export resolves to ADMIN despite being a GET")
    void theExportIsAdminEvenThoughItIsARead() {
        assertThat(ApiRoutePolicy.resolver(HttpMethod.GET, "/api/db/export"))
                .as("a bulk-exfiltration path is not a benign read")
                .isEqualTo(Access.ADMIN);
    }

    // ── case 3 · the forbidden heuristic, as a property ──────────────────────

    @Test
    @DisplayName("no GET-only row matches both /api/db/export and /api/data")
    void theGetMeansViewerHeuristicCannotComeBack() {
        List<String> culpables = new ArrayList<>();

        for (RoutePolicy fila : ApiRoutePolicy.TABLE) {
            boolean soloGet = fila.methods().equals(java.util.Set.of(HttpMethod.GET));
            if (!soloGet || fila.access() == Access.ADMIN) {
                continue;
            }
            boolean cubreExport = fila.patterns().stream()
                    .anyMatch(p -> ApiRoutePolicy.coincide(p, "/api/db/export"));
            boolean cubreCatalogo = fila.patterns().stream()
                    .anyMatch(p -> ApiRoutePolicy.coincide(p, "/api/data"));
            if (cubreExport && cubreCatalogo) {
                culpables.add(fila.patterns().toString());
            }
        }

        assertThat(culpables)
                .as("encoded as a property, not an instance: a row shaped like 'GET /api/** is a read' "
                        + "would hand the whole database to every account, and asserting only on "
                        + "/api/db/export would miss it under any other pattern")
                .isEmpty();
    }

    // ── the generic sweep ────────────────────────────────────────────────────

    @Test
    @DisplayName("no earlier row subsumes a later one")
    void noEarlierRowSubsumesALaterOne() {
        List<String> sombras = new ArrayList<>();

        for (int i = 0; i < ApiRoutePolicy.TABLE.size(); i++) {
            RoutePolicy anterior = ApiRoutePolicy.TABLE.get(i);
            for (int j = i + 1; j < ApiRoutePolicy.TABLE.size(); j++) {
                RoutePolicy posterior = ApiRoutePolicy.TABLE.get(j);
                if (anterior.access() == posterior.access()) {
                    continue;   // same verdict — shadowing changes nothing
                }
                if (esPreflight(anterior)) {
                    // The ONE deliberate exception, carved out by identity rather
                    // than by loosening the rule. `OPTIONS /**` does sit above the
                    // ADMIN rows and does win for OPTIONS — that is required, not
                    // accidental: a CORS preflight carries no Authorization header
                    // by definition, so gating it makes every cross-origin request
                    // fail before the real one is ever sent. It shadows nothing
                    // that matters because a preflight never reaches a handler;
                    // Spring answers it from the CORS configuration and stops.
                    // Covered on its own by preflightIsPermittedButReachesNoHandler.
                    continue;
                }
                if (!metodosSeCruzan(anterior, posterior)) {
                    continue;
                }
                for (String patronPosterior : posterior.patterns()) {
                    String muestra = concretar(patronPosterior);
                    boolean tapado = anterior.patterns().stream()
                            .anyMatch(p -> ApiRoutePolicy.coincide(p, muestra));
                    if (tapado) {
                        sombras.add(String.format(
                                "fila %d %s tapa a la fila %d %s (ej. %s)",
                                i, anterior.patterns(), j, posterior.patterns(), muestra));
                    }
                }
            }
        }

        assertThat(sombras).as("[reglas que nunca se van a consultar]").isEmpty();
    }

    @Test
    @DisplayName("the table is not empty — a vacuous sweep proves nothing")
    void theSweepIsNotVacuous() {
        // The classic way a guard like this rots: the collection it iterates
        // becomes empty and every assertion above passes for free.
        assertThat(ApiRoutePolicy.TABLE).hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("preflight is permitted everywhere, including on ADMIN paths")
    void preflightIsPermittedButReachesNoHandler() {
        // The carve-out the sweep skips, asserted on its own so it is a decision
        // with a test rather than a hole with a comment.
        assertThat(ApiRoutePolicy.resolver(HttpMethod.OPTIONS, "/api/usuarios")).isEqualTo(Access.PERMIT);
        assertThat(ApiRoutePolicy.resolver(HttpMethod.OPTIONS, "/api/db/export")).isEqualTo(Access.PERMIT);
        assertThat(ApiRoutePolicy.resolver(HttpMethod.OPTIONS, "/api/scrape")).isEqualTo(Access.PERMIT);

        // …and permitting OPTIONS grants nothing on the real verbs.
        assertThat(ApiRoutePolicy.resolver(HttpMethod.GET, "/api/db/export")).isEqualTo(Access.ADMIN);
        assertThat(ApiRoutePolicy.resolver(HttpMethod.POST, "/api/scrape")).isEqualTo(Access.ADMIN);
        assertThat(ApiRoutePolicy.resolver(HttpMethod.GET, "/api/usuarios")).isEqualTo(Access.ADMIN);
    }

    @Test
    @DisplayName("only the preflight row is exempt from the sweep")
    void onlyOneRowIsExemptFromTheSweep() {
        long exentas = ApiRoutePolicy.TABLE.stream().filter(RoutePolicyShadowingTest::esPreflight).count();

        assertThat(exentas)
                .as("the exemption is by identity; a second row slipping into it would be an "
                        + "un-swept rule wearing the preflight's clothes")
                .isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean esPreflight(RoutePolicy fila) {
        return fila.methods().equals(java.util.Set.of(HttpMethod.OPTIONS))
                && fila.patterns().equals(List.of("/**"))
                && fila.access() == Access.PERMIT;
    }

    private static boolean metodosSeCruzan(RoutePolicy a, RoutePolicy b) {
        if (a.cualquierMetodo() || b.cualquierMetodo()) {
            return true;
        }
        return a.methods().stream().anyMatch(b.methods()::contains);
    }

    /** Turns a pattern into a concrete path a matcher can be asked about. */
    private static String concretar(String patron) {
        return patron.replace("/**", "/x").replaceAll("\\{[^}]+}", "x");
    }
}
