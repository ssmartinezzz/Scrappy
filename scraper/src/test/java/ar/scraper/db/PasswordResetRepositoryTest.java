package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 6 — the reset token's storage and its
 * single-use guarantee.
 *
 * <p>The concurrency test is the reason this class exists. "Single use" written
 * as read-then-check-then-update holds perfectly in every sequential test and
 * fails exactly when two requests arrive together — which is both the race an
 * attacker would try to win and the one an impatient user causes by
 * double-clicking. Asserting it sequentially would prove nothing about the
 * property people actually rely on.</p>
 */
@Epic("Security")
@Feature("Password reset")
@Story("Reset tokens are hashed at rest and consumable exactly once")
@DisplayName("PasswordResetRepository")
class PasswordResetRepositoryTest extends PostgresTestBase {

    private static final Instant AHORA = Instant.parse("2026-08-19T12:00:00Z");

    private PasswordResetRepository repo;
    private UsuarioRepository usuarios;
    private UUID ana;

    @BeforeEach
    void setUp() {
        repo = new PasswordResetRepository(dataSource());
        usuarios = new UsuarioRepository(dataSource());
        usuarios.crear("ana", "ana@example.com", "$argon2id$x", false);
        ana = usuarios.buscarActivaPorUsername("ana").orElseThrow().id();
    }

    @Test
    @DisplayName("a fresh token consumes once and reports its owner")
    void aFreshTokenConsumesOnce() {
        repo.crear(ana, "token-crudo", AHORA.plus(Duration.ofMinutes(30)));

        assertThat(repo.consumir("token-crudo", AHORA)).contains(ana);
    }

    @Test
    @DisplayName("a second use of the same token is refused")
    void aSecondUseIsRefused() {
        repo.crear(ana, "token-crudo", AHORA.plus(Duration.ofMinutes(30)));
        repo.consumir("token-crudo", AHORA);

        assertThat(repo.consumir("token-crudo", AHORA)).isEmpty();
    }

    @Test
    @DisplayName("an expired token is refused")
    void anExpiredTokenIsRefused() {
        repo.crear(ana, "token-crudo", AHORA.plus(Duration.ofMinutes(30)));

        assertThat(repo.consumir("token-crudo", AHORA.plus(Duration.ofMinutes(31)))).isEmpty();
    }

    @Test
    @DisplayName("an unknown token is refused and says nothing about why")
    void anUnknownTokenIsRefused() {
        assertThat(repo.consumir("nunca-existio", AHORA)).isEmpty();
    }

    @Test
    @DisplayName("the raw token is never stored")
    void theRawTokenIsNeverStored() throws Exception {
        repo.crear(ana, "token-crudo", AHORA.plus(Duration.ofMinutes(30)));

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT token_hash FROM password_reset_token")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .as("a database dump must not be a stack of working reset links")
                    .isNotEqualTo("token-crudo")
                    .matches("[0-9a-f]{64}");
        }
    }

    @Test
    @DisplayName("two concurrent consumes: exactly one wins")
    void twoConcurrentConsumesHaveExactlyOneWinner() throws Exception {
        repo.crear(ana, "token-disputado", AHORA.plus(Duration.ofMinutes(30)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<UUID>> intento = () -> repo.consumir("token-disputado", AHORA);
            List<Future<Optional<UUID>>> resultados = pool.invokeAll(List.of(intento, intento));

            long ganadores = 0;
            for (Future<Optional<UUID>> f : resultados) {
                if (f.get().isPresent()) {
                    ganadores++;
                }
            }
            assertThat(ganadores)
                    .as("read-check-update passes every sequential test and fails exactly here, "
                            + "which is the case an attacker races and a double-click causes")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("consuming inside a transaction that rolls back leaves the token usable")
    void aRolledBackConsumeDoesNotBurnTheToken() throws Exception {
        repo.crear(ana, "token-crudo", AHORA.plus(Duration.ofMinutes(30)));

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            assertThat(repo.consumir(c, "token-crudo", AHORA)).contains(ana);
            c.rollback();
        }

        assertThat(repo.consumir("token-crudo", AHORA))
                .as("a link burnt by a change that then failed would send the user back for another "
                        + "one, for a reason nobody could explain")
                .contains(ana);
    }

    @Test
    @DisplayName("voiding pending tokens leaves no other live link behind")
    void voidingPendingTokensClearsTheRest() throws Exception {
        repo.crear(ana, "token-1", AHORA.plus(Duration.ofMinutes(30)));
        repo.crear(ana, "token-2", AHORA.plus(Duration.ofMinutes(30)));
        repo.crear(ana, "token-3", AHORA.plus(Duration.ofMinutes(30)));

        try (Connection c = dataSource().getConnection()) {
            assertThat(repo.anularPendientesDe(c, ana, AHORA)).isEqualTo(3);
        }

        assertThat(repo.consumir("token-1", AHORA)).isEmpty();
        assertThat(repo.consumir("token-2", AHORA)).isEmpty();
        assertThat(repo.consumir("token-3", AHORA)).isEmpty();
    }
}
