package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED→GREEN coverage for {@link RequiredEnvVarsGuard} — closes the
 * verify-phase CRITICAL-1 finding (decouple-services-postgres, scoped
 * correction): spec "Environment-Only Configuration" MUST fail fast when a
 * required env var is missing, instead of silently defaulting.
 *
 * <p>Exercises the {@link org.springframework.boot.env.EnvironmentPostProcessor}
 * directly against a {@link MockEnvironment} rather than booting a full Spring
 * context — this keeps the test fast/DB-free and lets us assert the exact
 * missing-variable message without depending on real OS environment state
 * (which would make the test non-deterministic across machines/CI).</p>
 */
@Epic("Configuration")
@Feature("Environment-only config fail-fast")
@Story("RequiredEnvVarsGuard")
@DisplayName("RequiredEnvVarsGuard — fail-fast on missing required env vars (default profile), dev/test profile opt-out")
class RequiredEnvVarsGuardTest {

    private final RequiredEnvVarsGuard guard = new RequiredEnvVarsGuard();

    @Test
    @DisplayName("default profile + all required vars unset → throws naming every missing variable")
    void defaultProfileMissingAllVarsThrows() {
        MockEnvironment env = new MockEnvironment();
        // no active profile set — default profile, no fallbacks allowed

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(env, null));

        assertTrue(ex.getMessage().contains("DATABASE_URL"), "message should name DATABASE_URL");
        assertTrue(ex.getMessage().contains("DATABASE_USERNAME"), "message should name DATABASE_USERNAME");
        assertTrue(ex.getMessage().contains("DATABASE_PASSWORD"), "message should name DATABASE_PASSWORD");
        assertTrue(ex.getMessage().contains("APP_CORS_ALLOWED_ORIGINS"), "message should name APP_CORS_ALLOWED_ORIGINS");
    }

    @Test
    @DisplayName("default profile + one required var missing → throws naming only that variable")
    void defaultProfileOneVarMissingThrows() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "jdbc:postgresql://127.0.0.1:5432/scraper");
        env.setProperty("DATABASE_USERNAME", "postgres");
        env.setProperty("DATABASE_PASSWORD", ""); // explicit empty (trust-auth) — NOT "missing"
        // APP_CORS_ALLOWED_ORIGINS intentionally left unset

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessEnvironment(env, null));

        assertTrue(ex.getMessage().contains("APP_CORS_ALLOWED_ORIGINS"), "message should name the missing var");
        assertTrue(!ex.getMessage().contains("DATABASE_URL"), "message should not name a present var");
        assertTrue(!ex.getMessage().contains("DATABASE_PASSWORD"), "explicit empty value must not count as missing");
    }

    @Test
    @DisplayName("explicit empty DATABASE_PASSWORD (installer trust-auth convention) does not count as missing")
    void explicitEmptyPasswordIsNotMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "jdbc:postgresql://127.0.0.1:5432/scraper");
        env.setProperty("DATABASE_USERNAME", "postgres");
        env.setProperty("DATABASE_PASSWORD", "");
        env.setProperty("APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173");

        assertDoesNotThrow(() -> guard.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("all required vars present → does not throw")
    void allVarsPresentDoesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "jdbc:postgresql://127.0.0.1:5432/scraper");
        env.setProperty("DATABASE_USERNAME", "postgres");
        env.setProperty("DATABASE_PASSWORD", "postgres");
        env.setProperty("APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173");

        assertDoesNotThrow(() -> guard.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("dev profile active → skips the guard even with all vars unset")
    void devProfileSkipsGuard() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        assertDoesNotThrow(() -> guard.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("test profile active → skips the guard even with all vars unset")
    void testProfileSkipsGuard() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        assertDoesNotThrow(() -> guard.postProcessEnvironment(env, null));
    }
}
