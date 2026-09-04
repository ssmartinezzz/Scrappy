package ar.scraper.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Serves the checked-in OpenAPI contract ({@code docs/openapi.yaml}) to
 * anyone, streamed from the classpath — never a filesystem path relative to
 * {@code docs/}, which does not exist in Docker. {@code pom.xml}'s
 * {@code copy-resources} execution bundles the file at
 * {@code contract/openapi.yaml} (a neutral prefix Spring Boot never serves
 * directly). A missing classpath resource throws rather than returning an
 * empty 200 — {@code copy-resources} over a missing directory only warns.
 *
 * <p><b>The route is public because the response is filtered, not because the
 * whole contract became public.</b> Every operation carrying
 * {@code x-access: ADMIN} is stripped here, at serve time, so the body an
 * anonymous caller receives is exactly what a VIEWER may reach: the
 * {@code PERMIT} and {@code AUTHENTICATED} operations. The administrative
 * mutation surface the earlier ADMIN gate existed to hide —
 * {@code DELETE /api/db/productos}, {@code /api/agent/**},
 * {@code /api/usuarios/**} — is never written to the wire at all, which is
 * the difference between filtering and hiding a UI entry point.</p>
 *
 * <p>Filtering deliberately does NOT happen in the frontend: the full
 * document would still travel over the network and sit in devtools, readable
 * by anyone who opens the Network tab.</p>
 *
 * <p>The bundled resource itself stays untouched — {@code OpenApiRouteCoverageTest}
 * asserts it is byte-identical to {@code docs/openapi.yaml}, and that guard is
 * about the artefact, not about this response.</p>
 */
@RestController
public class OpenApiDocumentController {

    private static final Logger LOG = LoggerFactory.getLogger(OpenApiDocumentController.class);
    private static final String CLASSPATH_LOCATION = "contract/openapi.yaml";
    private static final MediaType APPLICATION_YAML =
            new MediaType("application", "yaml", StandardCharsets.UTF_8);

    /** Path-item keys that are operations. Anything else there is metadata and is kept as-is. */
    private static final Set<String> VERBOS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final String ACCESO_OCULTO = "ADMIN";

    /**
     * The filtered document is a pure function of a classpath resource, so it
     * is computed once. Volatile + a benign double computation under a race is
     * enough: two threads would produce the same string.
     */
    private volatile String documentoFiltrado;

    @GetMapping("/api/openapi.yaml")
    public ResponseEntity<String> servirContrato() {
        String cacheado = documentoFiltrado;
        if (cacheado == null) {
            cacheado = filtrar(leerDelClasspath());
            documentoFiltrado = cacheado;
        }
        return ResponseEntity.ok()
                .contentType(APPLICATION_YAML)
                .body(cacheado);
    }

    private static String leerDelClasspath() {
        Resource contrato = new ClassPathResource(CLASSPATH_LOCATION);
        if (!contrato.exists()) {
            LOG.error("Missing classpath resource {}: either the pom.xml copy-resources execution "
                    + "did not run, or the Docker build context lacks docs/openapi.yaml.",
                    CLASSPATH_LOCATION);
            throw new IllegalStateException("OpenAPI contract not found on the classpath at "
                    + CLASSPATH_LOCATION + " — the pom.xml copy-resources execution did not run, "
                    + "or the Docker build context lacks docs/openapi.yaml.");
        }
        try (InputStream in = contrato.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Drops every {@code x-access: ADMIN} operation. A path left with no
     * operation at all is dropped whole rather than served as an empty object:
     * an empty path item still names the route, which is the very thing being
     * withheld.
     *
     * <p>{@code info}, {@code servers}, {@code security}, {@code tags} and
     * {@code components} are untouched. A tag that ends up with no operation
     * under it is left in the list — it carries no path and no method, and
     * swagger-ui renders sections from operations, not from the declaration.</p>
     */
    @SuppressWarnings("unchecked")
    static String filtrar(String yamlCrudo) {
        Yaml lector = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object cargado = lector.load(yamlCrudo);
        if (!(cargado instanceof Map<?, ?>)) {
            throw new IllegalStateException("The bundled OpenAPI contract did not parse into a mapping.");
        }
        Map<String, Object> documento = (Map<String, Object>) cargado;

        if (documento.get("paths") instanceof Map<?, ?> paths) {
            Map<String, Object> conservados = filtrarPaths((Map<String, Object>) paths);
            documento.put("paths", conservados);
            // A tag left with no operations still names a surface the reader
            // cannot reach — `Usuarios`, `Cron`, `DB` and `LLM Agent` are
            // entirely ADMIN. swagger-ui renders nothing for them, but the
            // name alone is a hint, so they are dropped rather than served
            // empty.
            if (documento.get("tags") instanceof List<?> tags) {
                documento.put("tags", filtrarTags((List<Object>) tags, tagsEnUso(conservados)));
            }
        }
        return escritor().dump(documento);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filtrarPaths(Map<String, Object> paths) {
        Map<String, Object> conservados = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entrada : paths.entrySet()) {
            if (!(entrada.getValue() instanceof Map<?, ?> item)) {
                conservados.put(entrada.getKey(), entrada.getValue());
                continue;
            }
            Map<String, Object> filtrado = new LinkedHashMap<>();
            boolean quedaAlgunaOperacion = false;
            for (Map.Entry<String, Object> claveValor : ((Map<String, Object>) item).entrySet()) {
                boolean esOperacion = VERBOS.contains(
                        String.valueOf(claveValor.getKey()).toLowerCase(Locale.ROOT));
                if (esOperacion && esAdmin(claveValor.getValue())) {
                    continue;
                }
                filtrado.put(claveValor.getKey(), claveValor.getValue());
                quedaAlgunaOperacion |= esOperacion;
            }
            if (quedaAlgunaOperacion) {
                conservados.put(entrada.getKey(), filtrado);
            }
        }
        return conservados;
    }

    /** Every tag named by an operation that survived filtering. */
    @SuppressWarnings("unchecked")
    private static Set<String> tagsEnUso(Map<String, Object> paths) {
        Set<String> enUso = new LinkedHashSet<>();
        for (Object item : paths.values()) {
            if (!(item instanceof Map<?, ?> pathItem)) {
                continue;
            }
            for (Map.Entry<String, Object> claveValor : ((Map<String, Object>) pathItem).entrySet()) {
                boolean esOperacion = VERBOS.contains(
                        String.valueOf(claveValor.getKey()).toLowerCase(Locale.ROOT));
                if (esOperacion && claveValor.getValue() instanceof Map<?, ?> op
                        && op.get("tags") instanceof List<?> tags) {
                    tags.forEach(t -> enUso.add(String.valueOf(t)));
                }
            }
        }
        return enUso;
    }

    /** Keeps only the declared tags that {@link #tagsEnUso} still references. */
    private static List<Object> filtrarTags(List<Object> declarados, Set<String> enUso) {
        List<Object> conservados = new ArrayList<>();
        for (Object tag : declarados) {
            boolean referenciado = tag instanceof Map<?, ?> t
                    && enUso.contains(String.valueOf(t.get("name")));
            if (referenciado) {
                conservados.add(tag);
            }
        }
        return conservados;
    }

    private static boolean esAdmin(Object operacion) {
        return operacion instanceof Map<?, ?> op
                && ACCESO_OCULTO.equals(String.valueOf(op.get("x-access")));
    }

    private static Yaml escritor() {
        DumperOptions opciones = new DumperOptions();
        opciones.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opciones.setSplitLines(false);
        // The response declares charset=UTF-8, so an em dash should stay an em
        // dash. Without this snakeyaml escapes every non-ASCII character into
        // a numeric escape inside double quotes — still valid YAML, but this
        // document is meant to be read.
        opciones.setAllowUnicode(true);
        return new Yaml(opciones);
    }
}
