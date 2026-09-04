package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.security.SecurityConfig;
import ar.scraper.security.TokenService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/openapi.yaml} — filters ON, same pattern as
 * {@link ar.scraper.web.AuthEndpointsMeTest}: a real {@link SecurityConfig}
 * and {@link JwtAuthFilter} with a real signed token, so the
 * {@link ar.scraper.security.ApiRoutePolicy} row is what's proven.
 * {@link TokenService} must be imported too — {@code @WebMvcTest} registers
 * {@link jakarta.servlet.Filter}s but not ordinary {@code @Component}s.
 *
 * <p>The contract changed in {@code apidocs-public-filtered-document}: the
 * route is PERMIT, and what protects the administrative surface is the
 * <b>body</b>, not the status code. So the reachability tests below are worth
 * little on their own — the assertion that carries the change is
 * {@link #adminOperationsNeverCrossTheWire()}, and it is paired with a
 * positive control ({@link #theFilterDoesNotSimplyReturnNothing()}) because a
 * filter that returned an empty document would satisfy the absence check
 * perfectly.</p>
 */
@WebMvcTest(controllers = OpenApiDocumentController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, TokenService.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256",
        "app.cors.allowed-origins=http://localhost:5173"
})
@Epic("Security")
@Feature("Contract documentation")
@Story("GET /api/openapi.yaml — public, filtered to the non-ADMIN surface")
@DisplayName("OpenApiDocumentController — GET /api/openapi.yaml")
class OpenApiDocumentControllerTest {

    private static final Set<String> VERBOS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokens;

    @MockBean
    private UsuarioRepository usuarios;

    private String tokenDe(String rol) {
        UUID id = UUID.randomUUID();
        when(usuarios.autorizacionDe(id)).thenReturn(Optional.of(
                new UsuarioRepository.Autorizacion("u-" + rol.toLowerCase(), List.of(rol), null)));
        return tokens.emitir(id);
    }

    private String cuerpoAnonimo() throws Exception {
        return mockMvc.perform(get("/api/openapi.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsear(String yaml) {
        Object cargado = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        assertThat(cargado)
                .as("the served body must be a parseable YAML mapping, not prose or an error page")
                .isInstanceOf(Map.class);
        return (Map<String, Object>) cargado;
    }

    /** One {@code METHOD path} key per operation the served document actually declares. */
    @SuppressWarnings("unchecked")
    private static List<String> operacionesServidas(Map<String, Object> documento) {
        List<String> claves = new ArrayList<>();
        Object paths = documento.get("paths");
        if (!(paths instanceof Map<?, ?> mapa)) {
            return claves;
        }
        for (Map.Entry<String, Object> entrada : ((Map<String, Object>) mapa).entrySet()) {
            if (!(entrada.getValue() instanceof Map<?, ?> item)) {
                continue;
            }
            for (Map.Entry<String, Object> op : ((Map<String, Object>) item).entrySet()) {
                String verbo = String.valueOf(op.getKey()).toLowerCase(Locale.ROOT);
                if (VERBOS.contains(verbo)) {
                    claves.add(verbo.toUpperCase(Locale.ROOT) + " " + entrada.getKey());
                }
            }
        }
        return claves;
    }

    /** The {@code x-access} value of every operation the served document declares. */
    @SuppressWarnings("unchecked")
    private static List<String> accesosServidos(Map<String, Object> documento) {
        List<String> accesos = new ArrayList<>();
        Object paths = documento.get("paths");
        if (!(paths instanceof Map<?, ?> mapa)) {
            return accesos;
        }
        for (Object item : ((Map<String, Object>) mapa).values()) {
            if (!(item instanceof Map<?, ?> operaciones)) {
                continue;
            }
            for (Map.Entry<String, Object> op : ((Map<String, Object>) operaciones).entrySet()) {
                if (VERBOS.contains(String.valueOf(op.getKey()).toLowerCase(Locale.ROOT))
                        && op.getValue() instanceof Map<?, ?> cuerpo) {
                    accesos.add(String.valueOf(cuerpo.get("x-access")));
                }
            }
        }
        return accesos;
    }

    // ── Reachability: the same 200 for everyone ─────────────────────────────

    @Test
    @DisplayName("an anonymous request receives 200 with the YAML body")
    void anonymousReceivesTheDocument() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    if (body == null || body.isBlank()) {
                        throw new AssertionError("the document body must never be empty");
                    }
                    if (!body.contains("openapi:")) {
                        throw new AssertionError("the document body does not look like an OpenAPI contract");
                    }
                });
    }

    @Test
    @DisplayName("a VIEWER receives 200 — the role no longer decides reachability")
    void viewerReceivesTheDocument() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml")
                        .header("Authorization", "Bearer " + tokenDe("VIEWER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ADMIN receives 200 — and the same filtered body as everyone else")
    void adminReceivesTheSameFilteredDocument() throws Exception {
        String deAdmin = mockMvc.perform(get("/api/openapi.yaml")
                        .header("Authorization", "Bearer " + tokenDe("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(deAdmin)
                .as("there is one served document, not a per-role one — an ADMIN who wants the "
                        + "whole contract reads docs/openapi.yaml in the repository")
                .isEqualTo(cuerpoAnonimo());
    }

    // ── What actually protects the administrative surface: the body ─────────

    @Test
    @DisplayName("no x-access: ADMIN operation appears anywhere in the served body")
    void adminOperationsNeverCrossTheWire() throws Exception {
        String cuerpo = cuerpoAnonimo();
        Map<String, Object> documento = parsear(cuerpo);

        assertThat(documento.get("paths"))
                .as("a document with no paths at all would pass every absence check below for free")
                .isInstanceOf(Map.class);

        // Deliberately structural, not a substring sweep of the raw body: the
        // securitySchemes description explains the x-access vocabulary and
        // quotes "x-access: ADMIN" as prose, so a text search would fail on a
        // correctly filtered document.
        assertThat(accesosServidos(documento))
                .as("no served operation may still declare x-access: ADMIN")
                .isNotEmpty()
                .doesNotContain("ADMIN");

        List<String> servidas = operacionesServidas(documento);
        assertThat(servidas)
                .as("these are the administrative operations the earlier ADMIN gate existed to "
                        + "withhold — filtering has to withhold them too, or it bought nothing")
                .doesNotContain(
                        "DELETE /api/db/productos",
                        "DELETE /api/db/ml",
                        "GET /api/db/export",
                        "DELETE /api/usuarios/{username}",
                        "PUT /api/usuarios/{username}/rol",
                        "POST /api/agent/apply",
                        "POST /api/agent/chat",
                        "POST /api/scrape",
                        "POST /api/ml/renormalizar");
    }

    @Test
    @DisplayName("the paths those operations lived under are dropped whole, not served empty")
    void emptiedPathsAreDroppedEntirely() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) parsear(cuerpoAnonimo()).get("paths");

        assertThat(paths.keySet())
                .as("an empty path item still names the route, which is the thing being withheld")
                .doesNotContain("/api/db/productos", "/api/usuarios/{username}", "/api/agent/apply");

        for (Map.Entry<String, Object> entrada : paths.entrySet()) {
            assertThat(entrada.getValue())
                    .as("no served path may be an empty object: " + entrada.getKey())
                    .isInstanceOf(Map.class);
            assertThat((Map<?, ?>) entrada.getValue())
                    .as("no served path may be an empty object: " + entrada.getKey())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("a tag whose every operation was filtered out is not declared either")
    void tagsLeftWithoutOperationsAreDropped() throws Exception {
        Map<String, Object> documento = parsear(cuerpoAnonimo());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) documento.get("tags");
        List<String> nombres = tags.stream().map(t -> String.valueOf(t.get("name"))).toList();

        assertThat(nombres)
                .as("Usuarios, Cron, DB and LLM Agent are entirely ADMIN — swagger-ui renders "
                        + "nothing for them, but the name alone still points at the surface")
                .doesNotContain("Usuarios", "Cron", "DB", "LLM Agent");

        // Positive control: dropping every tag would satisfy the assertion above.
        assertThat(nombres)
                .as("the tags that still carry operations must survive")
                .contains("Auth", "Catálogo");

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) documento.get("paths");
        Set<String> referenciados = new LinkedHashSet<>();
        for (Object item : paths.values()) {
            ((Map<?, ?>) item).forEach((verbo, op) -> {
                if (op instanceof Map<?, ?> operacion && operacion.get("tags") instanceof List<?> t) {
                    t.forEach(x -> referenciados.add(String.valueOf(x)));
                }
            });
        }
        assertThat(nombres)
                .as("every declared tag must be referenced by a surviving operation")
                .allSatisfy(n -> assertThat(referenciados).contains(n));
    }

    // ── Positive control: an empty filter would pass every check above ──────

    @Test
    @DisplayName("positive control: the non-ADMIN surface is still there — PERMIT and AUTHENTICATED alike")
    void theFilterDoesNotSimplyReturnNothing() throws Exception {
        Map<String, Object> documento = parsear(cuerpoAnonimo());
        List<String> servidas = operacionesServidas(documento);

        assertThat(servidas)
                .as("a filter that returned an empty document would satisfy every absence "
                        + "assertion in this class perfectly")
                .hasSizeGreaterThan(30)
                .contains("GET /api/data", "POST /api/auth/login", "GET /api/openapi.yaml");

        assertThat(documento.keySet())
                .as("only paths are filtered — the rest of the document is served intact")
                .contains("openapi", "info", "servers", "security", "tags", "components");
    }
}
