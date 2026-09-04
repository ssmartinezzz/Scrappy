package ar.scraper.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authorization matrix, as data.
 *
 * <p>Keeping it in a list rather than scattered across {@code @PreAuthorize}
 * annotations is what makes it reviewable: the whole policy fits on one screen
 * and can be swept for overlaps by a test. An annotation per method cannot be
 * checked for the thing that actually goes wrong here, which is one rule
 * shadowing another.</p>
 *
 * <h3>Ordering discipline: bands, most specific first</h3>
 *
 * <p>First match wins. A row may only be preceded by rows that do not subsume
 * it, and {@code RoutePolicyShadowingTest} enforces that rather than trusting
 * it.</p>
 *
 * <h3>The two hazards, which fail in opposite directions</h3>
 *
 * <table>
 *   <tr><th></th><th>{@code /api/ml/**}</th><th>{@code GET /api/db/export}</th></tr>
 *   <tr><td>Shape</td>
 *       <td>a specific VIEWER <b>read</b> hidden behind a general ADMIN rule</td>
 *       <td>a specific ADMIN <b>read</b> a general VIEWER-read rule would capture</td></tr>
 *   <tr><td>Failure</td>
 *       <td>{@code GET /api/ml/estado} 403s a legitimate VIEWER — <b>fails closed</b>,
 *           visible, annoying</td>
 *       <td>the export becomes VIEWER-readable — <b>fails open</b>, silent,
 *           hands over the database</td></tr>
 *   <tr><td>Fix</td>
 *       <td><b>ordering</b>: rows 7-8 precede row 12</td>
 *       <td><b>absence</b>: there must be <i>no</i> general "GET means VIEWER" row
 *           anywhere</td></tr>
 * </table>
 *
 * <p>The second is the dangerous one and ordering cannot fix it — only <i>not
 * writing that row</i> can. That is why every read surface below is enumerated
 * explicitly instead of collapsing into {@code GET /api/**}.</p>
 *
 * <h3>No catch-all: {@code denyAll()} is genuinely reachable</h3>
 *
 * <p>There is deliberately no final {@code ANY /** → AUTHENTICATED} row. With
 * one, the posture would really be <i>authenticated</i>-by-default, and a new
 * admin endpoint that nobody wrote a row for would silently be VIEWER-reachable.
 * The cost — you must add a row — is paid as a red build in
 * {@code RouteCoverageTest}, which is the cheapest place to pay it.</p>
 */
public final class ApiRoutePolicy {

    private ApiRoutePolicy() {
    }

    public enum Access {
        /** No token required. */
        PERMIT,
        /** Any valid token: ADMIN and VIEWER alike. Ownership scoping happens in the query layer. */
        AUTHENTICATED,
        /** ADMIN only; a VIEWER's valid token is rejected. */
        ADMIN
    }

    /**
     * @param methods empty means "any method". A set rather than one method per
     *                row because the matrix genuinely needs "writes to X are
     *                ADMIN, reads are not", and splitting that into three rows
     *                triples the row count exactly where ordering mistakes live.
     */
    public record RoutePolicy(Set<HttpMethod> methods, List<String> patterns, Access access, String nota) {

        public RoutePolicy(Set<HttpMethod> methods, List<String> patterns, Access access) {
            this(methods, patterns, access, null);
        }

        public boolean cualquierMetodo() {
            return methods.isEmpty();
        }
    }

    private static final Set<HttpMethod> CUALQUIERA = Set.of();

    public static final List<RoutePolicy> TABLE = List.of(

            // ── Band A · unauthenticated ─────────────────────────────────────
            // Preflight carries no Authorization header by definition; gating it
            // makes every cross-origin request fail before the real one is sent.
            new RoutePolicy(Set.of(HttpMethod.OPTIONS), List.of("/**"), Access.PERMIT,
                    "CORS preflight"),
            new RoutePolicy(Set.of(HttpMethod.POST), List.of("/api/auth/login"), Access.PERMIT),
            // POST rotates, DELETE logs out. Logout must work with an expired
            // access token — that is the usual state of a session being ended.
            new RoutePolicy(Set.of(HttpMethod.POST, HttpMethod.DELETE),
                    List.of("/api/auth/refresh"), Access.PERMIT),
            // Whoever needs these is by definition locked out and holds no token.
            new RoutePolicy(Set.of(HttpMethod.POST),
                    List.of("/api/auth/password-reset/request"), Access.PERMIT),
            new RoutePolicy(Set.of(HttpMethod.POST),
                    List.of("/api/auth/password-reset/confirm"), Access.PERMIT),
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/"), Access.PERMIT,
                    "RootController — liveness"),
            // Public because the response is FILTERED, not because the whole
            // contract went public: OpenApiDocumentController strips every
            // x-access: ADMIN operation before serialising, so the body never
            // enumerates the administrative mutation surface (DELETE
            // /api/db/productos, /api/agent/**, /api/usuarios/**) that made this
            // row ADMIN in the first place. What an anonymous caller gets is
            // exactly what a VIEWER may reach.
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/api/openapi.yaml"), Access.PERMIT,
                    "the contract itself, filtered down to PERMIT + AUTHENTICATED at serve time"),

            // ── Band B · carve-outs that MUST precede a Band C wildcard ──────
            //
            // These two exist SOLELY because the /api/ml/** ADMIN row below would
            // otherwise swallow them. Tidying them down into Band D breaks nothing
            // visibly and silently re-creates the bug the next time row 12 is
            // touched. Leave them here.
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/api/ml/estado"), Access.AUTHENTICATED,
                    "carve-out: precede /api/ml/**"),
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/api/ml/resultado"), Access.AUTHENTICATED,
                    "carve-out: precede /api/ml/**"),

            // ── Band C · ADMIN ───────────────────────────────────────────────
            // The routes do not exist yet (slice 9). The rule lands first so they
            // are born gated: there is no instant in which a user-administration
            // route exists without an ADMIN rule above it.
            new RoutePolicy(CUALQUIERA, List.of("/api/usuarios", "/api/usuarios/**"), Access.ADMIN,
                    "row ships before the routes — D10"),
            new RoutePolicy(CUALQUIERA, List.of("/api/agent/**"), Access.ADMIN,
                    "the whole LLM catalog agent, chat and apply alike"),
            new RoutePolicy(CUALQUIERA, List.of("/api/cron", "/api/cron/**"), Access.ADMIN,
                    "reads too: the schedule is operational configuration"),
            new RoutePolicy(CUALQUIERA, List.of("/api/ml/**"), Access.ADMIN,
                    "mutations; the two reads were matched above"),
            new RoutePolicy(CUALQUIERA, List.of("/api/db/**"), Access.ADMIN,
                    "includes GET /api/db/export — a bulk-exfiltration read, not a benign one"),
            new RoutePolicy(Set.of(HttpMethod.POST), List.of("/api/scrape"), Access.ADMIN),
            // Sin esta fila la ruta da 403 y RouteCoverageTest rompe el build:
            // la tabla no tiene catch-all, termina en denyAll().
            new RoutePolicy(Set.of(HttpMethod.POST), List.of("/api/scrape/cancel"), Access.ADMIN),
            new RoutePolicy(Set.of(HttpMethod.GET),  List.of("/api/scrape/interrupted"), Access.ADMIN),
            new RoutePolicy(Set.of(HttpMethod.POST), List.of("/api/scrape/resume"), Access.ADMIN),
            new RoutePolicy(Set.of(HttpMethod.PUT), List.of("/api/config"), Access.ADMIN),
            new RoutePolicy(Set.of(HttpMethod.POST, HttpMethod.DELETE),
                    List.of("/api/sitios", "/api/sitios/**"), Access.ADMIN,
                    "GET /api/sitios is not matched here — method specificity, no carve-out needed"),
            new RoutePolicy(Set.of(HttpMethod.DELETE), List.of("/api/data"), Access.ADMIN,
                    "soft-deletes a catalogue product — shared data, not personal. INFERRED"),
            new RoutePolicy(Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE),
                    List.of("/api/financiacion/presets", "/api/financiacion/presets/**"), Access.ADMIN,
                    "one `activo` flag changes everyone's calculation — shared config. INFERRED"),

            // ── Band D · any authenticated subject ───────────────────────────
            // Ownership scoping happens in the query layer (slice 8), not here:
            // these rows say "you may reach this", not "you may see everything".
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/api/auth/me"), Access.AUTHENTICATED,
                    "deliberately NOT Band A — answering 'who am I' to an anonymous caller is an oracle"),
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/api/status"), Access.AUTHENTICATED,
                    "gated like everything else — deliberately not on the permit list"),
            new RoutePolicy(Set.of(HttpMethod.GET),
                    List.of("/api/data", "/api/facets", "/api/csv", "/api/producto/**"),
                    Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET),
                    List.of("/api/tendencias", "/api/historial"), Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET),
                    List.of("/api/grupos", "/api/buscar-externo"), Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET),
                    List.of("/api/mejores", "/api/marcas-browser"), Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET),
                    List.of("/api/inflacion", "/api/recomendacion"), Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET),
                    List.of("/api/financiacion/presets"), Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/api/sitios"), Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET),
                    List.of("/api/outfits", "/api/outfits/builder", "/api/suplementos/**"),
                    Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE),
                    List.of("/api/outfits/save", "/api/outfits/saved", "/api/outfits/saved/**"),
                    Access.AUTHENTICATED, "own rows only — scoped in slice 8"),
            new RoutePolicy(Set.of(HttpMethod.POST, HttpMethod.DELETE),
                    List.of("/api/outfits/feedback"), Access.AUTHENTICATED, "own rows only"),
            new RoutePolicy(Set.of(HttpMethod.GET), List.of("/api/recomendados"), Access.AUTHENTICATED),
            new RoutePolicy(Set.of(HttpMethod.POST),
                    List.of("/api/recomendados/feedback"), Access.AUTHENTICATED, "own rows only"),
            new RoutePolicy(Set.of(HttpMethod.POST, HttpMethod.DELETE),
                    List.of("/api/recomendados/dismiss-categoria"), Access.AUTHENTICATED, "own rows only"),
            new RoutePolicy(Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE),
                    List.of("/api/favoritos"), Access.AUTHENTICATED, "own rows only")

            // …and then denyAll(). There is no catch-all on purpose.
    );

    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final Map<String, PathPattern> COMPILADOS = new LinkedHashMap<>();

    static {
        for (RoutePolicy row : TABLE) {
            for (String patron : row.patterns()) {
                COMPILADOS.computeIfAbsent(patron, PARSER::parse);
            }
        }
    }

    /**
     * Resolves a request the way the filter chain would.
     *
     * <p>Exists so the shadowing tests can assert on the <b>outcome</b> rather
     * than on row indices — an index assertion passes happily when somebody
     * reorders two rows together, which is exactly the mistake worth catching.
     *
     * @return {@code null} when no row matches, i.e. the request would hit
     *         {@code denyAll()}.
     */
    public static Access resolver(HttpMethod metodo, String path) {
        PathContainer contenedor = PathContainer.parsePath(path);
        for (RoutePolicy row : TABLE) {
            if (!row.cualquierMetodo() && !row.methods().contains(metodo)) {
                continue;
            }
            for (String patron : row.patterns()) {
                if (COMPILADOS.get(patron).matches(contenedor)) {
                    return row.access();
                }
            }
        }
        return null;
    }

    /** Does {@code patron} match {@code path}? Exposed for the subsumption sweep. */
    public static boolean coincide(String patron, String path) {
        return COMPILADOS.computeIfAbsent(patron, PARSER::parse)
                .matches(PathContainer.parsePath(path));
    }
}
