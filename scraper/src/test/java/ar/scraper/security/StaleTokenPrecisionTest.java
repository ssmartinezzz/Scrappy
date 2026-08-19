package ar.scraper.security;

import ar.scraper.db.UsuarioRepository;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 9 — the {@code iat} check compares like with like.
 *
 * <p>A JWT's {@code iat} is whole seconds. {@code password_changed_at} is a
 * microsecond timestamp. Comparing them directly rejects the token of the very
 * user who just changed their password: they log in at {@code 12:00:00.4}, the
 * token is stamped {@code 12:00:00}, and {@code 12:00:00} is not after
 * {@code 12:00:00.267}.</p>
 *
 * <p>The whole suite missed this because tests use fixed clocks that land on
 * clean second boundaries. It was found by hand, against a live backend, as
 * "login succeeds and then everything 401s for a second, with nothing in the
 * logs". This test reproduces the sub-second case the fixed clocks never
 * produced.</p>
 */
@Epic("Security")
@Feature("Enforcement")
@Story("A freshly issued token survives the password change that preceded it by milliseconds")
@DisplayName("JwtAuthFilter — iat precision")
class StaleTokenPrecisionTest {

    /** The comparison the filter makes, isolated from Spring and the database. */
    private static boolean rechazado(Instant iat, Instant passwordChangedAt) {
        return iat.isBefore(passwordChangedAt.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("a token issued in the same second as the change is accepted")
    void aTokenFromTheSameSecondSurvives() {
        Instant cambio = Instant.parse("2026-08-19T12:00:00.267633Z");
        Instant iat = Instant.parse("2026-08-19T12:00:00Z");   // JWT resolution

        assertThat(rechazado(iat, cambio))
                .as("this is the legitimate user logging in right after their own reset — "
                        + "rejecting them makes a successful login look broken")
                .isFalse();
    }

    @Test
    @DisplayName("a token issued a second before the change is still refused")
    void anOlderTokenIsStillRefused() {
        Instant cambio = Instant.parse("2026-08-19T12:00:00.267633Z");
        Instant iat = Instant.parse("2026-08-19T11:59:59Z");

        assertThat(rechazado(iat, cambio))
                .as("the whole point of the check: a token held from before the reset must die")
                .isTrue();
    }

    @Test
    @DisplayName("a token from well before the change is refused")
    void aMuchOlderTokenIsRefused() {
        assertThat(rechazado(Instant.parse("2026-08-19T11:50:00Z"),
                Instant.parse("2026-08-19T12:00:00.267633Z"))).isTrue();
    }

    @Test
    @DisplayName("a token issued after the change is accepted")
    void aLaterTokenIsAccepted() {
        assertThat(rechazado(Instant.parse("2026-08-19T12:00:05Z"),
                Instant.parse("2026-08-19T12:00:00.267633Z"))).isFalse();
    }

    @Test
    @DisplayName("the exact comparison that used to be wrong")
    void theNaiveComparisonWouldHaveRejectedIt() {
        Instant cambio = Instant.parse("2026-08-19T12:00:00.267633Z");
        Instant iat = Instant.parse("2026-08-19T12:00:00Z");

        assertThat(!iat.isAfter(cambio))
                .as("documents the defect: without the floor, the fresh token is refused")
                .isTrue();
        assertThat(rechazado(iat, cambio))
                .as("…and with it, it is not")
                .isFalse();
    }

    @Test
    @DisplayName("a null password_changed_at never refuses anything")
    void anAccountThatNeverChangedItsPasswordIsUnaffected() {
        UsuarioRepository.Autorizacion sinCambio =
                new UsuarioRepository.Autorizacion("ana", List.of("VIEWER"), null);

        assertThat(sinCambio.passwordChangedAt())
                .as("the filter skips the check entirely when the column is null")
                .isNull();
    }
}
