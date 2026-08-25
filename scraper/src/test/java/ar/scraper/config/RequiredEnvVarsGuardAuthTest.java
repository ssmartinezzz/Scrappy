package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user-accounts-and-roles, slice 2 — the five authentication variables join the
 * fail-fast guard.
 *
 * <p>They are required rather than defaulted because this repository is public:
 * a working default for a JWT signing secret or a bootstrap admin password
 * would be shared by every clone, which is the same as having no secret at all.
 * Failing to start, naming the variable, is the only safe behaviour.</p>
 *
 * <p><b>Blank is rejected here, unlike {@code DATABASE_PASSWORD}.</b> The
 * existing guard deliberately treats an explicitly-empty value as present,
 * because the installer writes an empty password for local trust-auth Postgres
 * — a real configuration choice. No such analogue exists for a signing secret
 * or an admin password: an empty one is not a choice, it is an unsigned token
 * and an account anybody can log into. The two rules differ because the two
 * situations differ, and this test pins both halves so neither drifts into the
 * other.</p>
 */
@Epic("Configuration")
@Feature("Environment-only config fail-fast")
@Story("RequiredEnvVarsGuard — authentication variables")
@DisplayName("RequiredEnvVarsGuard — the auth secrets have no public-repo default")
class RequiredEnvVarsGuardAuthTest {

    private final RequiredEnvVarsGuard guard = new RequiredEnvVarsGuard();

    private static final String[] AUTH_VARS = {
            "AUTH_JWT_SECRET",
            "ADMIN_BOOTSTRAP_USERNAME",
            "ADMIN_BOOTSTRAP_PASSWORD",
            "CLI_SERVICE_ACCOUNT_USERNAME",
            "CLI_SERVICE_ACCOUNT_PASSWORD",
    };

    @Test
    @DisplayName("a missing AUTH_JWT_SECRET fails startup, naming it")
    void missingJwtSecretFailsNamingIt() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(entornoSin("AUTH_JWT_SECRET"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET");
    }

    @Test
    @DisplayName("a missing ADMIN_BOOTSTRAP_PASSWORD fails startup, naming it")
    void missingBootstrapPasswordFailsNamingIt() {
        MockEnvironment env = entornoSin("ADMIN_BOOTSTRAP_PASSWORD");

        assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_BOOTSTRAP_PASSWORD");
    }

    @Test
    @DisplayName("every one of the five is required, one at a time")
    void eachAuthVariableIsRequiredOnItsOwn() {
        for (String var : AUTH_VARS) {
            MockEnvironment env = entornoSin(var);

            assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
                    .as("%s must be required", var)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(var);
        }
    }

    @Test
    @DisplayName("a blank auth secret is rejected — unlike a blank DATABASE_PASSWORD")
    void blankAuthSecretIsRejectedEvenThoughBlankDatabasePasswordIsNot() {
        for (String var : AUTH_VARS) {
            MockEnvironment env = entornoCompleto();
            env.setProperty(var, "   ");

            assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
                    .as("a blank %s is an absent secret wearing a value's clothes", var)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(var);
        }
    }

    @Test
    @DisplayName("a blank DATABASE_PASSWORD still starts — the trust-auth convention is untouched")
    void blankDatabasePasswordStillStarts() {
        MockEnvironment env = entornoCompleto();
        env.setProperty("DATABASE_PASSWORD", "");

        assertThatCode(() -> guard.postProcessEnvironment(env, null))
                .as("the installer writes an empty password for local trust-auth Postgres")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a fully-configured environment starts")
    void fullyConfiguredEnvironmentStarts() {
        assertThatCode(() -> guard.postProcessEnvironment(entornoCompleto(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the test profile still skips the whole check")
    void testProfileStillSkips() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        assertThatCode(() -> guard.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the message names every missing variable at once, not just the first")
    void theMessageNamesAllOfThem() {
        MockEnvironment env = entornoSin("AUTH_JWT_SECRET", "CLI_SERVICE_ACCOUNT_PASSWORD");

        assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("naming one at a time turns setup into a guessing game")
                        .contains("AUTH_JWT_SECRET")
                        .contains("CLI_SERVICE_ACCOUNT_PASSWORD"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final Map<String, String> ENTORNO_COMPLETO = Map.of(
            "DATABASE_URL", "jdbc:postgresql://127.0.0.1:5432/scraper",
            "DATABASE_USERNAME", "postgres",
            "DATABASE_PASSWORD", "postgres",
            "APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173",
            "AUTH_JWT_SECRET", "un-secreto-de-al-menos-32-bytes-para-hs256",
            "ADMIN_BOOTSTRAP_USERNAME", "admin",
            "ADMIN_BOOTSTRAP_PASSWORD", "una-password-real",
            "CLI_SERVICE_ACCOUNT_USERNAME", "cli",
            "CLI_SERVICE_ACCOUNT_PASSWORD", "otra-password-real");

    private static MockEnvironment entornoCompleto() {
        return entornoSin();
    }

    /** {@link MockEnvironment} cannot unset a key, so "absent" is built by omission. */
    private static MockEnvironment entornoSin(String... ausentes) {
        Set<String> omitidas = Set.of(ausentes);
        MockEnvironment env = new MockEnvironment();
        ENTORNO_COMPLETO.forEach((clave, valor) -> {
            if (!omitidas.contains(clave)) {
                env.setProperty(clave, valor);
            }
        });
        return env;
    }
}
