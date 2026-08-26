package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user-accounts-and-roles, slice 6 — SMTP is required <b>conditionally</b>.
 *
 * <p>This is the one place in the guard where a variable's requirement depends
 * on another variable, and the asymmetry is deliberate. {@code DATABASE_URL} is
 * unconditional because there is no configuration of this application that does
 * not use a database. Mail is different: the default channel writes the reset
 * link to the log and needs nothing at all, and demanding SMTP credentials from
 * every installation for a feature most of them will never point at an inbox
 * would defeat the whole zero-configuration install.</p>
 *
 * <p>Once the operator does select SMTP, though, a half-configured relay is
 * worse than none: the failure lands inside an async dispatch where nobody is
 * watching, and the user sees the same "check your email" either way. So the
 * five become as strictly required as the database ones.</p>
 */
@Epic("Configuration")
@Feature("Environment-only config fail-fast")
@Story("RequiredEnvVarsGuard — SMTP required only when the SMTP channel is selected")
@DisplayName("RequiredEnvVarsGuard — conditional SMTP block")
class RequiredEnvVarsGuardSmtpTest {

    private final RequiredEnvVarsGuard guard = new RequiredEnvVarsGuard();

    private static final Map<String, String> BASE = Map.of(
            "DATABASE_URL", "jdbc:postgresql://192.0.2.1:5432/no-existe",
            "DATABASE_USERNAME", "postgres",
            "DATABASE_PASSWORD", "postgres",
            "APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173",
            "AUTH_JWT_SECRET", "un-secreto-de-al-menos-32-bytes-para-hs256",
            "ADMIN_BOOTSTRAP_USERNAME", "admin",
            "ADMIN_BOOTSTRAP_PASSWORD", "una-password-real",
            "CLI_SERVICE_ACCOUNT_USERNAME", "cli",
            "CLI_SERVICE_ACCOUNT_PASSWORD", "otra-password-real");

    private static final Map<String, String> SMTP = Map.of(
            "SMTP_HOST", "smtp.example.com",
            "SMTP_PORT", "587",
            "SMTP_USERNAME", "apikey",
            "SMTP_PASSWORD", "una-clave",
            "SMTP_FROM_ADDRESS", "no-reply@example.com");

    @Test
    @DisplayName("with the channel unset, no SMTP variable is required")
    void theDefaultChannelRequiresNothingNew() {
        assertThatCode(() -> guard.postProcessEnvironment(entorno(Set.of()), null))
                .as("the console channel is the default and needs no mail server at all")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with the channel set to console, still nothing is required")
    void selectingConsoleExplicitlyRequiresNothingNew() {
        MockEnvironment env = entorno(Set.of());
        env.setProperty("PASSWORD_RESET_CHANNEL", "console");

        assertThatCode(() -> guard.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("selecting smtp with nothing configured fails, naming all five")
    void selectingSmtpWithoutConfigurationFailsNamingEveryVariable() {
        MockEnvironment env = entorno(Set.of());
        env.setProperty("PASSWORD_RESET_CHANNEL", "smtp");

        assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_HOST")
                .hasMessageContaining("SMTP_PORT")
                .hasMessageContaining("SMTP_USERNAME")
                .hasMessageContaining("SMTP_PASSWORD")
                .hasMessageContaining("SMTP_FROM_ADDRESS");
    }

    @Test
    @DisplayName("each SMTP variable is required on its own once the channel is smtp")
    void eachSmtpVariableIsRequiredOnItsOwn() {
        for (String faltante : SMTP.keySet()) {
            MockEnvironment env = entorno(SMTP.keySet());
            env.getPropertySources().remove("mockProperties");
            MockEnvironment completo = entorno(SMTP.keySet());
            completo.setProperty("PASSWORD_RESET_CHANNEL", "smtp");
            completo.setProperty(faltante, "");

            assertThatThrownBy(() -> guard.postProcessEnvironment(completo, null))
                    .as("a half-configured relay fails inside an async dispatch, where nobody sees it")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(faltante);
        }
    }

    @Test
    @DisplayName("smtp fully configured starts normally")
    void smtpFullyConfiguredStarts() {
        MockEnvironment env = entorno(SMTP.keySet());
        env.setProperty("PASSWORD_RESET_CHANNEL", "smtp");

        assertThatCode(() -> guard.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the selector is matched case-insensitively — SMTP and smtp are the same choice")
    void theSelectorIsCaseInsensitive() {
        MockEnvironment env = entorno(Set.of());
        env.setProperty("PASSWORD_RESET_CHANNEL", "SMTP");

        assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
                .as("a config that silently means 'console' because of capitalisation would be "
                        + "a reset flow that quietly stops mailing anybody")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_HOST");
    }

    private static MockEnvironment entorno(Set<String> conSmtp) {
        MockEnvironment env = new MockEnvironment();
        BASE.forEach(env::setProperty);
        SMTP.forEach((clave, valor) -> {
            if (conSmtp.contains(clave)) {
                env.setProperty(clave, valor);
            }
        });
        return env;
    }
}
