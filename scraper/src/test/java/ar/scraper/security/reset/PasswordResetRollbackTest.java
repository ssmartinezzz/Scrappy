package ar.scraper.security.reset;

import ar.scraper.db.PasswordResetRepository;
import ar.scraper.db.RefreshTokenRepository;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.FaultInjection;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestRepositories;
import ar.scraper.db.support.TestTransactions;
import ar.scraper.security.PasswordHasher;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Authentication")
@Feature("Password reset")
@Story("Confirming is atomic")
@DisplayName("PasswordResetService.confirmar — a consumed token never survives an unchanged password")
class PasswordResetRollbackTest extends PostgresTestBase {

    private static final String TOKEN = "token-crudo";

    private UsuarioRepository usuarios;
    private PasswordResetRepository tokens;
    private RefreshTokenRepository refrescos;
    private PasswordHasher hasher;
    private PasswordResetService service;
    private UUID ana;

    @BeforeEach
    void setUp() {
        usuarios = TestRepositories.usuarios(dataSource());
        tokens = TestRepositories.passwordResets(dataSource());
        refrescos = TestRepositories.refreshTokens(dataSource());
        hasher = new PasswordHasher();
        service = TestTransactions.proxy(new PasswordResetService(usuarios, tokens, refrescos, hasher,
                (destino, enlace) -> { }, new ResetRateLimiter(Clock.systemUTC()), Clock.systemUTC(),
                "http://localhost:5173", Runnable::run), TestTransactions.manager(dataSource()));

        usuarios.crear("ana", "ana@example.com", hasher.hash("la-vieja-password"), false);
        ana = usuarios.buscarActivaPorUsername("ana").orElseThrow().id();
        tokens.crear(ana, TOKEN, Instant.now().plus(Duration.ofMinutes(30)));
    }

    private boolean passwordSigueSiendoLaVieja() {
        String hash = usuarios.buscarActivaPorUsername("ana").orElseThrow().passwordHash();
        return hasher.verify("la-vieja-password", hash);
    }

    @Test
    @DisplayName("a failure while writing the new hash leaves the token unconsumed")
    void aFailureWritingTheHashLeavesTheTokenUsable() throws Exception {
        try (var fault = FaultInjection.raiseOn(dataSource(), "usuario", "UPDATE", null)) {
            assertThatThrownBy(() -> service.confirmar(TOKEN, "una-password-nueva"))
                    .isInstanceOf(UsuarioRepository.DatabaseException.class);
        }

        assertThat(passwordSigueSiendoLaVieja()).isTrue();
        assertThat(service.confirmar(TOKEN, "una-password-nueva"))
                .as("the link was not burnt by the change that failed").isTrue();
    }

    @Test
    @DisplayName("a password change that matches no row returns false and leaves the token unconsumed")
    void anUnchangedPasswordDoesNotBurnTheToken() throws Exception {
        try (var fault = FaultInjection.skipOn(dataSource(), "usuario", "UPDATE", null)) {
            assertThat(service.confirmar(TOKEN, "una-password-nueva")).isFalse();
        }

        assertThat(passwordSigueSiendoLaVieja()).isTrue();
        assertThat(service.confirmar(TOKEN, "una-password-nueva")).isTrue();
    }

    @Test
    @DisplayName("a failure revoking sessions keeps the old password and the token")
    void aFailureRevokingSessionsChangesNothing() throws Exception {
        refrescos.crear(ana, "sesion", UUID.randomUUID(), "n1", Instant.now().plus(Duration.ofDays(14)));
        try (var fault = FaultInjection.raiseOn(dataSource(), "refresh_token", "UPDATE", null)) {
            assertThatThrownBy(() -> service.confirmar(TOKEN, "una-password-nueva"))
                    .isInstanceOf(UsuarioRepository.DatabaseException.class);
        }

        assertThat(passwordSigueSiendoLaVieja()).isTrue();
        assertThat(service.confirmar(TOKEN, "una-password-nueva")).isTrue();
    }
}
