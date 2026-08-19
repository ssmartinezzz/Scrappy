package ar.scraper.web;

import ar.scraper.security.RefreshCookie;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS policy for the API-only backend (decouple-services-postgres, Batch 3,
 * design D6). The frontend is its own independently-deployed service (Vite dev
 * server on {@code http://localhost:5173} in local dev; a real origin in prod),
 * so cross-origin requests to {@code /api/**} must be explicitly allowed.
 *
 * <p>Origin allow-list comes from {@code app.cors.allowed-origins}
 * ({@code application.properties} resolves it from the
 * {@code APP_CORS_ALLOWED_ORIGINS} env var): a comma-separated list of origins.</p>
 *
 * <h3>Two mappings, and the order is load-bearing</h3>
 *
 * <p>{@code UrlBasedCorsConfigurationSource} returns the <b>first</b> mapping
 * that matches, so the specific refresh-path rule has to be registered before
 * the {@code /**} catch-all. Registered the other way round it would never be
 * consulted, credentials would silently stay off, and the browser would drop
 * the refresh cookie on every cross-origin refresh — a session that expires
 * every fifteen minutes with no error anywhere, which reads as a bug rather
 * than as a misconfiguration. It is the same first-match-wins hazard the
 * authorization route table has, in a second place.</p>
 *
 * <p><b>Credentials are true for exactly one path.</b> Everything else is a
 * pure bearer-header API and carries no ambient credential, so
 * {@code allowCredentials} stays {@code false} there. Turning it on globally
 * would be a broad grant bought for one endpoint's needs.</p>
 *
 * <h3>Why the wildcard is rejected at startup</h3>
 *
 * <p>Credentialed CORS forbids {@code Access-Control-Allow-Origin: *}, and
 * Spring enforces that in {@code validateAllowCredentials()} — at <b>request</b>
 * time. Left to Spring, a deployment with {@code APP_CORS_ALLOWED_ORIGINS=*}
 * would boot happily and then answer 500 on the first login, with a stack trace
 * pointing at CORS internals rather than at the variable. Failing at startup,
 * naming the variable, costs nothing and turns a confusing outage into a
 * message. {@code allowedHeaders("*")} is deliberately left alone: Spring echoes
 * the concrete requested header names back, so it is valid under credentialed
 * CORS.</p>
 *
 * <p>No fallback default on the {@code @Value} (scoped correction, verify-report
 * CRITICAL-1): a silent default here would defeat
 * {@code RequiredEnvVarsGuard}'s fail-fast for {@code APP_CORS_ALLOWED_ORIGINS}.</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final String[] METODOS =
            {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"};

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @PostConstruct
    void validarOrigenes() {
        String[] origins = origenes();
        if (origins.length == 0) {
            throw new IllegalStateException(
                    "APP_CORS_ALLOWED_ORIGINS está vacía. El endpoint de refresco usa CORS con "
                            + "credenciales, que exige una lista de orígenes exacta.");
        }
        for (String origin : origins) {
            if ("*".equals(origin)) {
                throw new IllegalStateException(
                        "APP_CORS_ALLOWED_ORIGINS no puede ser '*': el endpoint de refresco usa CORS "
                                + "con credenciales, y el comodín está prohibido ahí. Poné la lista "
                                + "exacta de orígenes del frontend.");
            }
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = origenes();

        // FIRST — first match wins. See the class javadoc.
        registry.addMapping(RefreshCookie.PATH)
                .allowedOrigins(origins)
                .allowedMethods(METODOS)
                .allowedHeaders("*")
                .allowCredentials(true);

        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods(METODOS)
                .allowedHeaders("*")
                .allowCredentials(false);
    }

    private String[] origenes() {
        if (allowedOrigins == null) {
            return new String[0];
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
