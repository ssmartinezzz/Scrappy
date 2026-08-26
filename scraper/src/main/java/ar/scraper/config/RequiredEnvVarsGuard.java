package ar.scraper.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast guard for spec requirement "Environment-Only Configuration"
 * (decouple-services-postgres, design D6; scoped correction closing
 * verify-report CRITICAL-1): the default profile MUST NOT silently default a
 * missing required env var to a local resource — it must fail startup fast
 * with a clear error naming every missing variable.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor}, fired during
 * {@code ApplicationEnvironmentPreparedEvent} — before any bean (including
 * {@code DataSource}/{@code CorsConfig}) is created, so the failure happens
 * as early as possible and with a precise message instead of a generic
 * Spring placeholder-resolution stack trace.</p>
 *
 * <p><b>dev/test profile opt-out</b>: local developer convenience and the
 * test suite both need to run without every required var set. Both cases are
 * carried by {@code application-dev.properties} (fallback values, active only
 * via {@code SPRING_PROFILES_ACTIVE=dev}) and the {@code test} profile
 * (activated for the whole Maven test JVM via the {@code spring.profiles.active}
 * system property in {@code pom.xml}'s surefire config) respectively — this
 * guard simply skips its check when either profile is active.</p>
 *
 * <p><b>Presence, not blankness — for infrastructure variables</b>: a variable
 * in {@link #REQUIRED_VARS} counts as "set" if the environment has it at all,
 * even as an empty string. This matters for {@code DATABASE_PASSWORD}: the
 * installer intentionally writes an empty value for local trust-auth Postgres
 * ({@code _tools/pgsql}, {@code initdb -A trust}) — that is an explicit
 * configuration choice, not a silently-missing resource, and must not be
 * rejected by this guard.</p>
 *
 * <p><b>Blankness too — for the authentication secrets</b> in
 * {@link #REQUIRED_SECRETS}. The trust-auth reasoning has no analogue there:
 * there is no deployment in which an empty JWT signing secret or an empty
 * bootstrap admin password is a deliberate choice. An empty signing secret is
 * an unsigned token, and an empty admin password is an account anybody can log
 * into. Both are the failure this guard exists to prevent, wearing a value's
 * clothes, so blank counts as missing for these five and only for these five.</p>
 *
 * <p>Neither list may carry a default. This repository is public: a working
 * default secret is shared by every clone, which is the same as having no
 * secret at all.</p>
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
     * Authentication secrets (user-accounts-and-roles, slice 2). Required
     * <b>and non-blank</b>, unlike {@link #REQUIRED_VARS} above — see the
     * class javadoc for why the two rules differ.
     */
    static final List<String> REQUIRED_SECRETS = List.of(
            "AUTH_JWT_SECRET",
            "ADMIN_BOOTSTRAP_USERNAME",
            "ADMIN_BOOTSTRAP_PASSWORD",
            "CLI_SERVICE_ACCOUNT_USERNAME",
            "CLI_SERVICE_ACCOUNT_PASSWORD"
    );

    /**
     * SMTP is required <b>conditionally</b> — only when the operator has selected
     * that channel. Listing it unconditionally would force every installation to
     * configure a mail server for a feature whose default adapter writes to the
     * log and needs nothing, which is the opposite of what this project's
     * zero-configuration install is for.
     */
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
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        if ("smtp".equalsIgnoreCase(String.valueOf(environment.getProperty(SELECTOR_DE_CANAL)).trim())) {
            for (String key : SMTP_VARS) {
                String value = environment.getProperty(key);
                if (value == null || value.isBlank()) {
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
     * Checks whether the bootstrap admin account already exists in the database.
     * When it does, {@code ADMIN_BOOTSTRAP_PASSWORD} is inert — the seeder's
     * {@code ON CONFLICT DO NOTHING} won't consume it — so requiring it would
     * block startup for no reason. A fresh install still needs it because the
     * account doesn't exist yet.
     *
     * <p>On any failure (DB down, driver missing, query error) this returns
     * {@code false} — the guard falls back to requiring the password, which is
     * the safe default for a fresh install.</p>
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
