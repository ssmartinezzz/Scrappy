package ar.scraper.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS policy for the API-only backend (decouple-services-postgres, Batch 3,
 * design D6). The frontend is now its own independently-deployed service
 * (Vite dev server on {@code http://localhost:5173} in local dev; a real
 * origin in prod), so cross-origin requests to {@code /api/**} must be
 * explicitly allowed.
 *
 * <p>Origin allow-list comes from {@code app.cors.allowed-origins}
 * ({@code application.properties} resolves it from the
 * {@code APP_CORS_ALLOWED_ORIGINS} env var — task 3.1 spike outcome:
 * comma-separated list of origins, e.g.
 * {@code https://app.example.com,https://admin.example.com}). No credentials
 * (cookies/auth headers) exist in this API, so {@code allowCredentials} stays
 * {@code false} — this also means the allow-list may safely include multiple
 * origins without the stricter same-origin-echo requirement credentialed CORS
 * would impose.</p>
 *
 * <p>No fallback default on the {@code @Value} below (scoped correction,
 * verify-report CRITICAL-1): a second silent default here would defeat
 * {@code RequiredEnvVarsGuard}/{@code application.properties}'s fail-fast
 * behavior for {@code APP_CORS_ALLOWED_ORIGINS}. Local dev fallback lives in
 * {@code application-dev.properties} (SPRING_PROFILES_ACTIVE=dev); tests that
 * import this config directly (e.g. {@code CorsConfigTest}) supply the
 * property explicitly via {@code @TestPropertySource}.</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
