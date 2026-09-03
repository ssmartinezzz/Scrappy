package ar.scraper.security;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The live route scanner, shared by {@link RouteCoverageTest} and
 * {@code OpenApiRouteCoverageTest} (`openapi-swagger-docs`).
 *
 * <p>Extracted verbatim rather than duplicated (`design.md` ADR-2): knowledge
 * of how Spring mappings are declared would otherwise live in two places, and
 * a scanner blind spot fixed in one copy would silently stop requiring a live
 * route to be documented in the other — the fail-open direction. Precedent:
 * {@code DocumentedRollback}'s javadoc was extracted for the same reason.</p>
 */
final class LiveRoutes {

    private LiveRoutes() {
    }

    record Ruta(HttpMethod metodo, String path) {}

    /** Reads the real {@code @*Mapping} annotations off every controller. */
    static List<Ruta> todas() {
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
    static String concretar(String path) {
        String concreto = path.replaceAll("\\{[^}]+}", "x");
        return concreto.isEmpty() ? "/" : concreto;
    }
}
