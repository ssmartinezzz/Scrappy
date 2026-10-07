package ar.scraper.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * Fail-fast guard for spec requirement "Environment-Only Configuration": the default profile MUST
 * NOT silently default a missing required env var to a local resource — it must fail startup fast
 * with a clear error naming every missing variable.
 */
public class RequiredEnvVarsGuard implements EnvironmentPostProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(RequiredEnvVarsGuard.class);

    static final List<String> REQUIRED_VARS = List.of(
            "DATABASE_URL",
            "DATABASE_USERNAME",
            "DATABASE_PASSWORD",
            "APP_CORS_ALLOWED_ORIGINS"
    );

    /**
     * Required and non-blank, unlike {@link #REQUIRED_VARS} above — see the class javadoc for why
     * the two rules differ.
     */
    static final List<String> REQUIRED_SECRETS = List.of(
            "AUTH_JWT_SECRET",
            "ADMIN_BOOTSTRAP_USERNAME",
            "ADMIN_BOOTSTRAP_PASSWORD",
            "CLI_SERVICE_ACCOUNT_USERNAME",
            "CLI_SERVICE_ACCOUNT_PASSWORD"
    );

    /** SMTP is required conditionally — only when the operator has selected that channel. */
    static final List<String> SMTP_VARS = List.of(
            "SMTP_HOST",
            "SMTP_PORT",
            "SMTP_USERNAME",
            "SMTP_PASSWORD",
            "SMTP_FROM_ADDRESS"
    );

    private static final String SELECTOR_DE_CANAL = "PASSWORD_RESET_CHANNEL";

    private static final List<String> SKIP_PROFILES = List.of("dev", "test");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isSkippedProfile(environment)) {
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_VARS) {
            if (!environment.containsProperty(key)) {
                missing.add(key);
            }
        }
        for (String key : REQUIRED_SECRETS) {
            if ("ADMIN_BOOTSTRAP_PASSWORD".equals(key) && adminExists(environment)) {
                continue;
            }
            String value = environment.getProperty(key);
            if (StringUtils.isBlank(value)) {
                missing.add(key);
            }
        }
        if ("smtp".equalsIgnoreCase(String.valueOf(environment.getProperty(SELECTOR_DE_CANAL)).trim())) {
            for (String key : SMTP_VARS) {
                String value = environment.getProperty(key);
                if (StringUtils.isBlank(value)) {
                    missing.add(key);
                }
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required environment variable(s): " + String.join(", ", missing) + ". "
                            + "Set them in the process environment before starting the backend — see the root "
                            + ".env.example for the full list. For local development without setting every var, "
                            + "run with SPRING_PROFILES_ACTIVE=dev (carries local Postgres/CORS fallbacks in "
                            + "application-dev.properties)."
            );
        }
    }

    private boolean isSkippedProfile(ConfigurableEnvironment environment) {
        for (String activeProfile : environment.getActiveProfiles()) {
            if (SKIP_PROFILES.contains(activeProfile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * When it does, {@code ADMIN_BOOTSTRAP_PASSWORD} is inert — the seeder's
     * {@code ON CONFLICT DO NOTHING} won't consume it — so requiring it would block startup for no
     * reason.
     */
    private boolean adminExists(ConfigurableEnvironment environment) {
        String url = environment.getProperty("DATABASE_URL");
        String username = environment.getProperty("DATABASE_USERNAME");
        String password = environment.getProperty("DATABASE_PASSWORD");
        String adminUsername = environment.getProperty("ADMIN_BOOTSTRAP_USERNAME");

        if (url == null || adminUsername == null) {
            return false;
        }

        try (Connection c = DriverManager.getConnection(url,
                username != null ? username : "",
                password != null ? password : "")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM usuario WHERE username = ?")) {
                ps.setString(1, adminUsername);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Exception e) {
            LOG.debug("[GUARD] Could not check admin existence, assuming fresh install: {}", e.getMessage());
            return false;
        }
    }
}
