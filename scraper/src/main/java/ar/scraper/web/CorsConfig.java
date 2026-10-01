package ar.scraper.web;

import ar.scraper.config.AllowedOrigins;
import ar.scraper.security.RefreshCookie;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS policy for the API-only backend. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final String[] METODOS =
            {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"};

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @PostConstruct
    void validarOrigenes() {
        AllowedOrigins.validar(AllowedOrigins.parsear(allowedOrigins));
    }

    /**
     * The login path also needs credentialed CORS, and the reason is easy to miss: the login
     * response is what plants the refresh cookie.
     */
    private static final String LOGIN_PATH = "/api/auth/login";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = origenes();

        registry.addMapping(RefreshCookie.PATH)
                .allowedOrigins(origins)
                .allowedMethods(METODOS)
                .allowedHeaders("*")
                .allowCredentials(true);

        // Also before the catch-all: see LOGIN_PATH's javadoc for why the credentialed surface is
        // two paths and not one.
        registry.addMapping(LOGIN_PATH)
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
        return AllowedOrigins.parsear(allowedOrigins).toArray(new String[0]);
    }
}
