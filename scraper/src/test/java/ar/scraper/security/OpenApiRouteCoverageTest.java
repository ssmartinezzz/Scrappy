package ar.scraper.security;

import ar.scraper.security.ApiRoutePolicy.Access;
import ar.scraper.security.LiveRoutes.Ruta;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `openapi-swagger-docs` — {@code docs/openapi.yaml} is documentation, not
 * runtime behaviour, so nothing forces it to stay honest except this test.
 *
 * <p><b>Guard limit — placement 1 of 3.</b> This test proves path, method and
 * access-level parity between the checked-in contract and the live
 * application, and NOTHING about response-shape accuracy. Every handler in
 * this codebase returns an untyped {@code ObjectNode}/{@code Object}, so no
 * automated check here — or anywhere — can confirm that a documented response
 * matches what a handler actually emits. That is reviewed by eye, not tested.
 * Parameter and status-code accuracy have the same limit; only path, method
 * and {@code x-access} are enforced.</p>
 *
 * <p>Two directions, reported together so neither can hide behind the other:
 * a route documented in the YAML but absent from {@link ApiRoutePolicy} would
 * silently 403 in production ("documented but denied"), and a route the
 * controllers actually serve but missing from the YAML is contract drift
 * nobody would notice ("live but undocumented"). Direction 2 scans the live
 * controllers via {@link LiveRoutes}, never {@code ApiRoutePolicy.TABLE}:
 * several policy patterns ({@code /api/agent/**}, {@code /api/db/**},
 * {@code /api/usuarios/**} and seven others) are wildcards and cannot be
 * enumerated back into concrete routes.</p>
 *
 * <p>Negative control (recorded prior failure: a scan that silently returns
 * nothing lets two empty sets compare equal and pass for free). Both
 * non-vacuousness assertions below are independent of, and run before, the
 * parity assertions.</p>
 */
@Epic("Security")
@Feature("Authorization matrix")
@Story("docs/openapi.yaml matches the live application, in both directions")
@DisplayName("docs/openapi.yaml — bidirectional drift guard")
class OpenApiRouteCoverageTest {

    private static final String CONTRACT_FILE = "openapi.yaml";
    private static final int MAX_NIVELES_HACIA_ARRIBA = 6;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cargarContrato() {
        Path path = ubicarContrato();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = yaml.load(in);
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path ubicarContrato() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < MAX_NIVELES_HACIA_ARRIBA && dir != null; i++) {
            Path candidate = dir.resolve("docs").resolve(CONTRACT_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("docs/" + CONTRACT_FILE + " no encontrado subiendo desde "
                + System.getProperty("user.dir"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths(Map<String, Object> contrato) {
        Object paths = contrato.get("paths");
        return paths == null ? Map.of() : (Map<String, Object>) paths;
    }

    /** One documented (method, path, operation-object) triple, flattened out of the nested YAML map. */
    private record OperacionDocumentada(HttpMethod metodo, String path, Map<String, Object> op) {}

    @SuppressWarnings("unchecked")
    private static List<OperacionDocumentada> operacionesDocumentadas(Map<String, Object> contrato) {
        List<OperacionDocumentada> ops = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : paths(contrato).entrySet()) {
            String path = pathEntry.getKey();
            if (!(pathEntry.getValue() instanceof Map<?, ?> methods)) {
                continue;
            }
            for (Map.Entry<?, ?> methodEntry : methods.entrySet()) {
                String verbo = String.valueOf(methodEntry.getKey());
                HttpMethod metodo;
                try {
                    metodo = HttpMethod.valueOf(verbo.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Not an HTTP verb key (there are none at this nesting level in
                    // this contract, but stay defensive rather than crash the guard).
                    continue;
                }
                Map<String, Object> op = methodEntry.getValue() instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of();
                ops.add(new OperacionDocumentada(metodo, path, op));
            }
        }
        return ops;
    }

    // ── Negative control: both sides must be provably non-vacuous ──────────

    @Test
    @DisplayName("the controller scan finds real routes — an empty sweep would prove nothing")
    void theControllerScanIsNotVacuous() {
        assertThat(LiveRoutes.todas())
                .as("if this scan silently returned nothing, direction 2 would pass for free")
                .hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("the YAML parse finds real paths and real operations — a key count alone would not prove it")
    void theYamlParseIsNotVacuous() {
        Map<String, Object> contrato = cargarContrato();
        Map<String, Object> paths = paths(contrato);

        assertThat(paths.size())
                .as("if paths: {} silently parsed as \"documented\", direction 1 would pass for free")
                .isGreaterThan(40);

        assertThat(operacionesDocumentadas(contrato).size())
                .as("a 75-key map whose values yield zero method entries would still pass the key-count check above")
                .isGreaterThan(40);
    }

    // ── Direction 1: documented → live ──────────────────────────────────────

    @Test
    @DisplayName("every documented path+method resolves to a real ApiRoutePolicy row, at the documented access level")
    void everyDocumentedRouteIsLiveAndAtTheRightAccess() {
        Map<String, Object> contrato = cargarContrato();
        List<String> offensas = new ArrayList<>();

        for (OperacionDocumentada op : operacionesDocumentadas(contrato)) {
            String concreto = LiveRoutes.concretar(op.path());
            Access resuelto = ApiRoutePolicy.resolver(op.metodo(), concreto);

            if (resuelto == null) {
                offensas.add("documented but denied: " + op.metodo() + " " + op.path()
                        + " — resolves to no ApiRoutePolicy row, so it would 403");
                continue;
            }

            String documentado = String.valueOf(op.op().get("x-access"));
            if (!documentado.equals(resuelto.name())) {
                offensas.add("access mismatch: " + op.metodo() + " " + op.path()
                        + " — openapi.yaml says " + documentado
                        + ", ApiRoutePolicy resolves " + resuelto.name());
                continue;
            }

            if (resuelto != Access.PERMIT && esExplicitamentePublico(op.op())) {
                offensas.add(op.metodo() + " " + op.path() + " is x-access " + documentado
                        + " but carries security: [], documenting it as public");
            }
        }

        assertThat(offensas)
                .as("each offender names the exact route and which direction failed")
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static boolean esExplicitamentePublico(Map<String, Object> op) {
        Object security = op.get("security");
        return security instanceof List<?> lista && lista.isEmpty();
    }

    // ── Direction 2: live → documented ──────────────────────────────────────

    @Test
    @DisplayName("every live mapping is documented in docs/openapi.yaml")
    void everyLiveRouteIsDocumented() {
        Map<String, Object> contrato = cargarContrato();
        Map<String, Object> paths = paths(contrato);
        List<String> offensas = new ArrayList<>();

        for (Ruta ruta : LiveRoutes.todas()) {
            Object methods = paths.get(ruta.path());
            boolean documentado = methods instanceof Map<?, ?> m
                    && m.containsKey(ruta.metodo().name().toLowerCase());

            if (!documentado) {
                offensas.add("live but undocumented: " + ruta.metodo() + " " + ruta.path()
                        + " — add paths." + ruta.path() + "." + ruta.metodo().name().toLowerCase()
                        + " to docs/openapi.yaml");
            }
        }

        assertThat(offensas)
                .as("each offender names the exact route, which the reflection scan actually found live")
                .isEmpty();
    }

    // ── x-access vocabulary parity ───────────────────────────────────────────

    // ── swagger-ui-admin-gated: the copy-resources execution didn't no-op ───

    @Test
    @DisplayName("the classpath-bundled contract is byte-identical to docs/openapi.yaml")
    void classpathContractIsByteIdenticalToTheCheckedInFile() throws IOException {
        // copy-resources only warns (and still succeeds) over a missing source
        // dir, so this catches both "not copied" and "copied a stale one".
        byte[] checkedIn = Files.readAllBytes(ubicarContrato());

        InputStream classpathStream = OpenApiRouteCoverageTest.class.getClassLoader()
                .getResourceAsStream("contract/openapi.yaml");
        assertThat(classpathStream)
                .as("contract/openapi.yaml must exist on the test classpath — "
                        + "either copy-resources did not run, or it copied from the wrong place")
                .isNotNull();

        byte[] onClasspath;
        try (InputStream in = classpathStream) {
            onClasspath = in.readAllBytes();
        }

        assertThat(onClasspath)
                .as("the classpath copy must be byte-identical to docs/openapi.yaml — "
                        + "a stale copy would silently serve outdated documentation")
                .isEqualTo(checkedIn);
    }

    @Test
    @DisplayName("every x-access value in the contract is a real ApiRoutePolicy.Access constant")
    void everyXAccessValueIsARealAccessConstant() {
        Map<String, Object> contrato = cargarContrato();
        List<String> desconocidos = new ArrayList<>();

        for (OperacionDocumentada op : operacionesDocumentadas(contrato)) {
            String valor = String.valueOf(op.op().get("x-access"));
            boolean valido = false;
            for (Access a : Access.values()) {
                if (a.name().equals(valor)) {
                    valido = true;
                    break;
                }
            }
            if (!valido) {
                desconocidos.add(op.metodo() + " " + op.path() + " has x-access '" + valor
                        + "', not one of " + java.util.Arrays.toString(Access.values()));
            }
        }

        assertThat(desconocidos)
                .as("x-access is meant to mirror ApiRoutePolicy.Access verbatim")
                .isEmpty();
    }
}
