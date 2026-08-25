package ar.scraper.security;

import ar.scraper.db.UsuarioRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * user-accounts-and-roles, slice 7 — the chain actually enforces the table.
 *
 * <p><b>Filters are ON.</b> No {@code standaloneSetup}, no
 * {@code addFilters = false}: both make a test structurally incapable of proving
 * anything about authorization, which is why the ~68 existing controller unit
 * tests are declared unable to speak to it. This one loads a real Spring context
 * with the real {@link SecurityConfig}, the real {@link JwtAuthFilter} and real
 * signed tokens.</p>
 *
 * <p>The handlers under test are a fixture rather than the production
 * controllers, and deliberately so: what is under test here is the <b>chain</b>,
 * and pulling in real controllers would drag their twelve collaborators along
 * without proving anything more about it. That the real routes line up with the
 * table is a different question, proved separately and by reflection in
 * {@link RouteCoverageTest} — two properties, two tests, neither pretending to
 * cover the other.</p>
 */
@WebMvcTest(controllers = SecurityFilterChainIT.RutasDePrueba.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, TokenService.class,
        SecurityFilterChainIT.RutasDePrueba.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256",
        "app.cors.allowed-origins=http://localhost:5173"
})
@Epic("Security")
@Feature("Enforcement")
@Story("Deny by default, ADMIN matrix, role re-read from the database")
@DisplayName("SecurityFilterChain — enforcement")
class SecurityFilterChainIT {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokens;

    @MockBean
    private UsuarioRepository usuarios;

    /** Mirrors the paths under test. See the class javadoc for why this is a fixture. */
    @RestController
    static class RutasDePrueba {
        @GetMapping("/api/status")            String status()      { return "{}"; }
        @GetMapping("/api/data")              String data()        { return "{}"; }
        @GetMapping("/api/ml/estado")         String mlEstado()    { return "{}"; }
        @GetMapping("/api/ml/resultado")      String mlResultado() { return "{}"; }
        @PostMapping("/api/ml/entrenar")      String mlEntrenar()  { return "{}"; }
        @GetMapping("/api/db/export")         String dbExport()    { return "{}"; }
        @PostMapping("/api/scrape")           String scrape()      { return "{}"; }
        @PutMapping("/api/config")            String config()      { return "{}"; }
        @GetMapping("/api/sitios")            String sitiosGet()   { return "{}"; }
        @PostMapping("/api/sitios")           String sitiosPost()  { return "{}"; }
        @GetMapping("/api/cron")              String cronGet()     { return "{}"; }
        @PostMapping("/api/agent/chat")       String agentChat()   { return "{}"; }
        @DeleteMapping("/api/data")           String borrarProd()  { return "{}"; }
        @GetMapping("/api/favoritos")         String favoritos()   { return "{}"; }
        @PostMapping("/api/auth/login")       String login(@RequestBody(required = false) Map<String, String> b) { return "{}"; }
        @GetMapping("/")                      String raiz()        { return "{}"; }
        @GetMapping("/api/sin-regla")         String sinRegla()    { return "{}"; }
        @GetMapping("/api/producto/{key}")    String producto()    { return "{}"; }
        @GetMapping("/error")                 String error()       { return "{}"; }
    }

    private void dadoUnUsuario(UUID id, String rol) {
        when(usuarios.autorizacionDe(id)).thenReturn(Optional.of(
                new UsuarioRepository.Autorizacion("u-" + rol.toLowerCase(), List.of(rol), null)));
    }

    private String tokenDe(UUID id, String rol) {
        dadoUnUsuario(id, rol);
        return tokens.emitir(id);
    }

    // ── 7.10 · deny by default ───────────────────────────────────────────────

    @Test
    @DisplayName("no token at all: even GET /api/status is 401")
    void withoutATokenEvenStatusIsRefused() throws Exception {
        mockMvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/data")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/favoritos")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a route with no rule is refused, not allowed")
    void anUnlistedRouteFallsThroughToDenyAll() throws Exception {
        mockMvc.perform(get("/api/sin-regla").header("Authorization", "Bearer " + tokenDe(ADMIN, "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the permitted entry points work with no credential")
    void thePermitListWorksUnauthenticated() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    // ── 7.17 · the ADMIN matrix ──────────────────────────────────────────────

    @Test
    @DisplayName("a VIEWER is refused every ADMIN-exclusive route")
    void viewerIsDeniedTheAdminMatrix() throws Exception {
        String token = tokenDe(VIEWER, "VIEWER");

        mockMvc.perform(post("/api/scrape").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agent/chat").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/cron").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/sitios").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ml/entrenar").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/db/export is denied to a VIEWER even though it is a read")
    void theExportIsDeniedToViewer() throws Exception {
        mockMvc.perform(get("/api/db/export")
                        .header("Authorization", "Bearer " + tokenDe(VIEWER, "VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/cron is denied too — cron is ADMIN for reads as well")
    void cronReadsAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/cron").header("Authorization", "Bearer " + tokenDe(VIEWER, "VIEWER")))
                .andExpect(status().isForbidden());
    }

    // ── 7.18 · the VIEWER baseline ───────────────────────────────────────────

    @Test
    @DisplayName("a VIEWER reaches every read surface, including the two ML reads")
    void viewerReachesTheReadBaseline() throws Exception {
        String token = tokenDe(VIEWER, "VIEWER");

        mockMvc.perform(get("/api/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/sitios").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/favoritos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ml/estado").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ml/resultado").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ADMIN reaches both the matrix and the baseline")
    void adminReachesEverything() throws Exception {
        String token = tokenDe(ADMIN, "ADMIN");

        mockMvc.perform(post("/api/scrape").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/db/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ── 7.11 / 7.12 / 7.13 · what the token is NOT allowed to decide ─────────

    @Test
    @DisplayName("a forged role in the body or in a header is ignored")
    void forgedRoleClaimsAreIgnored() throws Exception {
        String token = tokenDe(VIEWER, "VIEWER");

        mockMvc.perform(post("/api/scrape")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Role", "ADMIN")
                        .header("X-Gateway-Verified-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoConRol()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a deactivated account is refused on its very next request")
    void deactivationBitesImmediately() throws Exception {
        String token = tokenDe(VIEWER, "VIEWER");
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // The row stops coming back — which is what activo = FALSE looks like
        // from the filter's side.
        when(usuarios.autorizacionDe(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token issued before the password changed is refused")
    void aStaleTokenIsRefusedAfterAPasswordChange() throws Exception {
        String token = tokens.emitir(VIEWER);
        when(usuarios.autorizacionDe(VIEWER)).thenReturn(Optional.of(
                new UsuarioRepository.Autorizacion("u-viewer", List.of("VIEWER"),
                        Instant.now().plusSeconds(60))));

        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a garbage or unsigned token is refused")
    void garbageTokensAreRefused() throws Exception {
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer no-es-un-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/data").header("Authorization", "Basic YWRtaW46YWRtaW4="))
                .andExpect(status().isUnauthorized());
    }

    // ── 7.8 · the /error forward must not be swallowed ───────────────────────

    @Test
    @DisplayName("an ERROR dispatch is not blocked by denyAll()")
    void theErrorForwardIsNotBlockedByDenyAll() throws Exception {
        // Asserted on the DISPATCH TYPE rather than by throwing from a handler:
        // MockMvc rethrows the exception instead of running the container's error
        // dispatch, so a throwing handler proves nothing about /error here.
        //
        // The property that matters: Boot forwards unhandled errors to /error,
        // that forward matches no policy row, and denyAll() would turn every
        // genuine 500 into a confusing 403 that hides the original failure.
        mockMvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(result -> {
                    int codigo = result.getResponse().getStatus();
                    if (codigo == 401 || codigo == 403) {
                        throw new AssertionError(
                                "el dispatch de ERROR quedó bloqueado por la cadena (HTTP " + codigo
                                        + "): cada 500 real se vería como un " + codigo);
                    }
                });
    }

    @Test
    @DisplayName("a FORWARD dispatch is not blocked either")
    void theForwardDispatchIsNotBlocked() throws Exception {
        mockMvc.perform(get("/api/sin-regla").with(request -> {
                    request.setDispatcherType(jakarta.servlet.DispatcherType.FORWARD);
                    return request;
                }))
                .andExpect(result -> {
                    int codigo = result.getResponse().getStatus();
                    if (codigo == 401 || codigo == 403) {
                        throw new AssertionError("el dispatch de FORWARD quedó bloqueado (HTTP " + codigo + ")");
                    }
                });
    }

    // ── 7.15 · hostile path forms ────────────────────────────────────────────

    @Test
    @DisplayName("traversal and encoded forms do not sneak past an ADMIN rule")
    void hostilePathFormsDoNotBypassTheMatrix() throws Exception {
        String token = tokenDe(VIEWER, "VIEWER");

        for (String hostil : List.of(
                "/api/db/../db/export",
                "/api/db//export",
                "/api/DB/export")) {
            mockMvc.perform(get(hostil).header("Authorization", "Bearer " + token))
                    .andExpect(result -> {
                        int codigo = result.getResponse().getStatus();
                        if (codigo == 200) {
                            throw new AssertionError(
                                    "la forma hostil " + hostil + " llegó al handler con un token VIEWER");
                        }
                    });
        }
    }

    private static String cuerpoConRol() {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("role", "ADMIN");
        body.put("rol", "ADMIN");
        return body.toString();
    }
}
