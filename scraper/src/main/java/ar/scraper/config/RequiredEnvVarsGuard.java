package ar.scraper.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

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
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
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
}
