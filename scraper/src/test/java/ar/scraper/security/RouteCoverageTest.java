package ar.scraper.security;

import ar.scraper.security.ApiRoutePolicy.Access;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 7 — every live route resolves to an explicit
 * rule, never to {@code denyAll()}.
 *
 * <p>The policy table has no catch-all on purpose, which turns "somebody added
 * an endpoint and forgot the rule" from a silent hole into a failing build. This
 * is the test that makes that trade pay: without it, the missing row would only
 * be discovered by whoever got a 403 on a feature that used to work.</p>
 *
 * <p>The mappings are read by reflection off the controllers rather than from a
 * hand-kept list, because a hand-kept list is exactly the thing that stops being
 * updated. This scans for real.</p>
 *
 * <p><b>One row matches nothing, and that is correct.</b> {@code /api/usuarios/**}
 * is ADMIN before the routes exist (slice 9), so those endpoints are born gated —
 * there is no instant in which a user-administration route exists without a rule
 * above it. An unused row is tolerated; a mapping with no row is not. The
 * asymmetry is the whole point.</p>
 */
@Epic("Security")
@Feature("Authorization matrix")
@Story("No live mapping falls through to denyAll()")
@DisplayName("ApiRoutePolicy — coverage")
class RouteCoverageTest {

    private record Ruta(HttpMethod metodo, String path) {}

    @Test
    @DisplayName("every mapping the application registers resolves to an explicit rule")
    void everyLiveMappingIsCovered() {
        List<Ruta> rutas = rutasDeLaAplicacion();
        List<String> sinRegla = new ArrayList<>();

        for (Ruta ruta : rutas) {
            if (ApiRoutePolicy.resolver(ruta.metodo(), concretar(ruta.path())) == null) {
                sinRegla.add(ruta.metodo() + " " + ruta.path());
            }
        }

        assertThat(sinRegla)
                .as("a mapping with no rule is refused by denyAll() — which is the safe direction, "
                        + "but it means a working feature broke and nobody meant it to")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds real routes — an empty sweep would prove nothing")
    void theScanIsNotVacuous() {
        assertThat(rutasDeLaAplicacion())
                .as("if this scan silently returned nothing, the assertion above would pass for free")
                .hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("the permit list is exactly the six unauthenticated entry points")
    void thePermitListIsExactlyWhatWeExpect() {
        Set<String> permitidas = new LinkedHashSet<>();
        for (var fila : ApiRoutePolicy.TABLE) {
            if (fila.access() == Access.PERMIT) {
                fila.patterns().forEach(p -> permitidas.add(
                        (fila.cualquierMetodo() ? "ANY" : fila.methods()) + " " + p));
            }
        }

        assertThat(permitidas)
                .as("the permit list is the attack surface that needs no credential at all — "
                        + "it should be short enough to read, and any growth should be deliberate")
                .hasSize(6);
    }

    @Test
    @DisplayName("GET /api/status is gated, deliberately not permitted")
    void statusIsGatedLikeEverythingElse() {
        assertThat(ApiRoutePolicy.resolver(HttpMethod.GET, "/api/status"))
                .as("a settled decision: it reports what the scraper is doing, which is not "
                        + "public information just because it is cheap to serve")
                .isEqualTo(Access.AUTHENTICATED);
    }

    @Test
    @DisplayName("the /api/usuarios rule exists and currently matches no live mapping")
    void theUserAdminRowIsPresentAndUnused() {
        assertThat(ApiRoutePolicy.resolver(HttpMethod.POST, "/api/usuarios"))
                .as("the rule ships before the routes so they are born gated — D10")
                .isEqualTo(Access.ADMIN);

        assertThat(rutasDeLaAplicacion())
                .as("shipping a user-creation endpoint before enforcement would let anybody mint "
                        + "themselves an ADMIN account — strictly worse than not having it")
                .noneMatch(r -> r.path().startsWith("/api/usuarios"));
    }

    @Test
    @DisplayName("no route referencing the deleted favourites re-scrape survives anywhere")
    void theDeletedRescrapeIsGoneFromBothSides() {
        assertThat(rutasDeLaAplicacion()).noneMatch(r -> r.path().contains("rescrape"));
        assertThat(ApiRoutePolicy.TABLE)
                .as("a rule for an endpoint that no longer exists is dead policy that reads as live")
                .noneMatch(f -> f.patterns().stream().anyMatch(p -> p.contains("rescrape")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Reads the real {@code @*Mapping} annotations off every controller. */
    private static List<Ruta> rutasDeLaAplicacion() {
        List<Ruta> rutas = new ArrayList<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);

        for (BeanDefinition definicion : scanner.findCandidateComponents("ar.scraper")) {
            Class<?> clase;
            try {
                clase = Class.forName(definicion.getBeanClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            RequestMapping raiz = AnnotatedElementUtils.findMergedAnnotation(clase, RequestMapping.class);
            if (raiz == null) {
                continue;
            }
            String prefijo = raiz.value().length > 0 ? raiz.value()[0] : "";

            for (Method metodo : clase.getDeclaredMethods()) {
                RequestMapping mapeo = AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
                if (mapeo == null) {
                    continue;
                }
                String[] paths = mapeo.value().length > 0 ? mapeo.value() : new String[]{""};
                for (String path : paths) {
                    for (var verbo : mapeo.method()) {
                        rutas.add(new Ruta(HttpMethod.valueOf(verbo.name()), prefijo + path));
                    }
                }
            }
        }
        return rutas;
    }

    /** {@code /api/producto/{key}} → {@code /api/producto/x}, so a matcher can be asked. */
    private static String concretar(String path) {
        String concreto = path.replaceAll("\\{[^}]+}", "x");
        return concreto.isEmpty() ? "/" : concreto;
    }
}
