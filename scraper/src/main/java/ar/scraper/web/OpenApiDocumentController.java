package ar.scraper.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Serves the checked-in OpenAPI contract ({@code docs/openapi.yaml}) to
 * authenticated ADMINs, streamed from the classpath — never a filesystem
 * path relative to {@code docs/}, which does not exist in Docker.
 * {@code pom.xml}'s {@code copy-resources} execution bundles the file at
 * {@code contract/openapi.yaml} (a neutral prefix Spring Boot never serves
 * directly). A missing classpath resource throws rather than returning an
 * empty 200 — {@code copy-resources} over a missing directory only warns.
 */
@RestController
public class OpenApiDocumentController {

    private static final Logger LOG = LoggerFactory.getLogger(OpenApiDocumentController.class);
    private static final String CLASSPATH_LOCATION = "contract/openapi.yaml";
    private static final MediaType APPLICATION_YAML =
            new MediaType("application", "yaml", StandardCharsets.UTF_8);

    @GetMapping("/api/openapi.yaml")
    public ResponseEntity<Resource> servirContrato() {
        Resource contrato = new ClassPathResource(CLASSPATH_LOCATION);
        if (!contrato.exists()) {
            LOG.error("Missing classpath resource {}: either the pom.xml copy-resources execution "
                    + "did not run, or the Docker build context lacks docs/openapi.yaml.",
                    CLASSPATH_LOCATION);
            throw new IllegalStateException("OpenAPI contract not found on the classpath at "
                    + CLASSPATH_LOCATION + " — the pom.xml copy-resources execution did not run, "
                    + "or the Docker build context lacks docs/openapi.yaml.");
        }
        return ResponseEntity.ok()
                .contentType(APPLICATION_YAML)
                .body(contrato);
    }
}
