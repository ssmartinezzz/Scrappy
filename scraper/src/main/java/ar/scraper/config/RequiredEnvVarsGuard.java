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
 * <p><b>Presence, not blankness</b>: a variable counts as "set" if the
 * environment has it at all, even as an empty string. This matters for
 * {@code DATABASE_PASSWORD}: the installer intentionally writes an empty
 * value for local trust-auth Postgres ({@code _tools/pgsql}, {@code initdb -A
 * trust}) — that is an explicit configuration choice, not a silently-missing
 * resource, and must not be rejected by this guard.</p>
 */
public class RequiredEnvVarsGuard implements EnvironmentPostProcessor {

    static final List<String> REQUIRED_VARS = List.of(
            "DATABASE_URL",
            "DATABASE_USERNAME",
            "DATABASE_PASSWORD",
            "APP_CORS_ALLOWED_ORIGINS"
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
