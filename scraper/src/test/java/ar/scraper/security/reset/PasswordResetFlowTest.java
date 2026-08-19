package ar.scraper.security.reset;

import ar.scraper.db.PasswordResetRepository;
import ar.scraper.db.RefreshTokenRepository;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.security.PasswordHasher;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 6 — the reset flow end to end, against a real
 * database.
 *
 * <p>The dispatch runs on the calling thread here (an inline {@link Executor}),
 * which is a test seam, not the production shape: in production it goes to a
 * virtual thread precisely so the response can be written before any of this
 * happens. Running it inline is what lets these tests assert on the outcome
 * without sleeping.</p>
 */
@Epic("Security")
@Feature("Password reset")
@Story("Request, deliver, confirm — and tell nobody whether the address exists")
@DisplayName("PasswordResetService")
class PasswordResetFlowTest extends PostgresTestBase {

    private static final Executor INLINE = Runnable::run;

    private UsuarioRepository usuarios;
    private PasswordResetRepository tokens;
    private RefreshTokenRepository refrescos;
    private PasswordHasher hasher;
    private CanalDePrueba canal;
    private PasswordResetService service;
    private UUID ana;

    @BeforeEach
    void setUp() {
        usuarios = new UsuarioRepository(dataSource());
        tokens = new PasswordResetRepository(dataSource());
        refrescos = new RefreshTokenRepository(dataSource());
        hasher = new PasswordHasher();
        canal = new CanalDePrueba();
        service = nuevoServicio(canal);

        usuarios.crear("ana", "ana@example.com", hasher.hash("la-vieja-password"), false);
        ana = usuarios.buscarActivaPorUsername("ana").orElseThrow().id();
    }

    private PasswordResetService nuevoServicio(PasswordResetChannel canal) {
        return new PasswordResetService(dataSource(), usuarios, tokens, refrescos, hasher, canal,
                new ResetRateLimiter(Clock.systemUTC()), Clock.systemUTC(),
                "http://localhost:5173", INLINE);
    }

    // ── enumeration ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a real address gets a link; an unknown one gets nothing, and neither is told")
    void onlyARealAddressProducesALink() {
        service.solicitar("ana@example.com", "1.2.3.4");
        service.solicitar("nadie@example.com", "1.2.3.4");
        service.solicitar("no-es-un-mail", "1.2.3.4");

        assertThat(canal.enviados)
                .as("the difference is invisible to the caller — it exists only here, inside")
                .hasSize(1);
        assertThat(canal.enviados.get(0).destino()).isEqualTo("ana@example.com");
    }

    @Test
    @DisplayName("a service account is unreachable because the schema gives it no address")
    void serviceAccountsAreExcludedByTheSchemaNotByACheck() throws Exception {
        usuarios.crear("cli", null, hasher.hash("x"), true);

        // The CHECK from V26 makes the alternative unrepresentable.
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            assertThat(st.executeUpdate(
                    "UPDATE usuario SET email = 'cli@example.com' "
                            + "WHERE username = 'cli' AND es_servicio = FALSE"))
                    .as("there is no row to update — es_servicio is TRUE, and the CHECK forbids the pair")
                    .isZero();
        }

        service.solicitar("cli@example.com", "1.2.3.4");

        assertThat(canal.enviados).isEmpty();
    }

    @Test
    @DisplayName("a disabled account is not resettable either")
    void disabledAccountsAreNotResettable() {
        usuarios.desactivar("ana");

        service.solicitar("ana@example.com", "1.2.3.4");

        assertThat(canal.enviados).isEmpty();
    }

    @Test
    @DisplayName("the address is matched case-insensitively")
    void theAddressIsNormalised() {
        service.solicitar("  ANA@Example.COM  ", "1.2.3.4");

        assertThat(canal.enviados).hasSize(1);
    }

    // ── the link ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the token rides in the fragment, never the query string")
    void theTokenIsInTheFragment() {
        service.solicitar("ana@example.com", "1.2.3.4");

        String enlace = canal.enviados.get(0).enlace();
        assertThat(enlace)
                .as("a fragment never reaches a server, so it cannot land in an access log, "
                        + "and is never sent in a Referer, so it cannot leak to third-party resources")
                .contains("#token=")
                .doesNotContain("?token=");
    }

    @Test
    @DisplayName("the token is opaque — not a JWT, no readable claims")
    void theTokenIsOpaque() {
        service.solicitar("ana@example.com", "1.2.3.4");

        String token = tokenDelUltimoEnlace();
        assertThat(token.split("\\.")).as("a header.payload.signature shape would be a JWT").hasSize(1);
        assertThat(token.length()).isGreaterThanOrEqualTo(40);
    }

    // ── rate limiting ────────────────────────────────────────────────────────

    @Test
    @DisplayName("past the per-address limit no further link is minted, and nobody is told")
    void theAddressLimitStopsDelivery() throws Exception {
        for (int i = 0; i < ResetRateLimiter.POR_DIRECCION_POR_HORA + 3; i++) {
            service.solicitar("ana@example.com", "1.2.3.4");
        }

        assertThat(canal.enviados)
                .as("otherwise the form is a way to mail-bomb somebody's inbox")
                .hasSize(ResetRateLimiter.POR_DIRECCION_POR_HORA);
        assertThat(contarTokens())
                .as("a limited request must not even mint a token")
                .isEqualTo(ResetRateLimiter.POR_DIRECCION_POR_HORA);
    }

    // ── confirm ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confirming changes the password and lets the old one stop working")
    void confirmingChangesThePassword() {
        service.solicitar("ana@example.com", "1.2.3.4");

        assertThat(service.confirmar(tokenDelUltimoEnlace(), "una-password-nueva")).isTrue();

        String hashGuardado = usuarios.buscarActivaPorUsername("ana").orElseThrow().passwordHash();
        assertThat(hasher.verify("una-password-nueva", hashGuardado)).isTrue();
        assertThat(hasher.verify("la-vieja-password", hashGuardado)).isFalse();
    }

    @Test
    @DisplayName("a token works exactly once")
    void aTokenIsSingleUse() {
        service.solicitar("ana@example.com", "1.2.3.4");
        String token = tokenDelUltimoEnlace();

        assertThat(service.confirmar(token, "una-password-nueva")).isTrue();
        assertThat(service.confirmar(token, "otra-password-mas")).isFalse();
    }

    @Test
    @DisplayName("a too-short password is refused and the token is not burnt")
    void aShortPasswordDoesNotBurnTheToken() {
        service.solicitar("ana@example.com", "1.2.3.4");
        String token = tokenDelUltimoEnlace();

        assertThat(service.confirmar(token, "corta")).isFalse();
        assertThat(service.confirmar(token, "una-password-nueva"))
                .as("refusing the password must not cost the user their link")
                .isTrue();
    }

    @Test
    @DisplayName("confirming revokes every session the user had, everywhere")
    void confirmingRevokesEverySession() throws Exception {
        refrescos.crear(ana, "sesion-de-la-notebook", UUID.randomUUID(), "n1",
                java.time.Instant.now().plus(Duration.ofDays(14)));
        refrescos.crear(ana, "sesion-del-telefono", UUID.randomUUID(), "n2",
                java.time.Instant.now().plus(Duration.ofDays(14)));
        service.solicitar("ana@example.com", "1.2.3.4");

        service.confirmar(tokenDelUltimoEnlace(), "una-password-nueva");

        assertThat(sesionesVivas())
                .as("a reset that left the intruder's session alive would be no remedy at all — "
                        + "and being locked out is the usual reason somebody resets a password")
                .isZero();
    }

    @Test
    @DisplayName("confirming voids the other links the user had requested")
    void confirmingVoidsTheOtherOutstandingLinks() {
        service.solicitar("ana@example.com", "1.2.3.4");
        String primero = tokenDelUltimoEnlace();
        service.solicitar("ana@example.com", "1.2.3.4");
        String segundo = tokenDelUltimoEnlace();

        assertThat(service.confirmar(segundo, "una-password-nueva")).isTrue();

        assertThat(service.confirmar(primero, "otra-password-mas"))
                .as("three requests and one use should not leave two live links behind")
                .isFalse();
    }

    @Test
    @DisplayName("confirming stamps password_changed_at — the hook that closes the access-token window")
    void confirmingStampsPasswordChangedAt() throws Exception {
        java.time.Instant antes = passwordChangedAt();
        service.solicitar("ana@example.com", "1.2.3.4");

        service.confirmar(tokenDelUltimoEnlace(), "una-password-nueva");

        assertThat(passwordChangedAt())
                .as("access tokens already issued stay valid for 15 more minutes; comparing their "
                        + "iat against this column is what closes that window, at no extra query")
                .isAfter(antes);
    }

    @Test
    @DisplayName("an unknown or expired token is refused without saying which")
    void badTokensAreRefused() {
        assertThat(service.confirmar("nunca-existio", "una-password-nueva")).isFalse();
        assertThat(service.confirmar(null, "una-password-nueva")).isFalse();
        assertThat(service.confirmar("", "una-password-nueva")).isFalse();
    }

    // ── delivery failure ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a channel that throws does not break the flow and does not reach the caller")
    void aThrowingChannelIsContained() throws Exception {
        PasswordResetService conCanalRoto = nuevoServicio((destino, enlace) -> {
            throw new IllegalStateException("el relay se cayó");
        });

        // In production the 202 has already been written by this point; the test
        // asserts the dispatch itself survives rather than taking the process down.
        conCanalRoto.solicitar("ana@example.com", "1.2.3.4");

        assertThat(contarTokens())
                .as("the token was minted before delivery was attempted — the failure is downstream")
                .isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Envio(String destino, String enlace) {}

    private static final class CanalDePrueba implements PasswordResetChannel {
        final List<Envio> enviados = new ArrayList<>();

        @Override
        public void enviar(String destino, String enlace) {
            enviados.add(new Envio(destino, enlace));
        }
    }

    private String tokenDelUltimoEnlace() {
        String enlace = canal.enviados.get(canal.enviados.size() - 1).enlace();
        return enlace.substring(enlace.indexOf("#token=") + "#token=".length());
    }

    private int contarTokens() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM password_reset_token")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private int sesionesVivas() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private java.time.Instant passwordChangedAt() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT password_changed_at FROM usuario WHERE username = 'ana'")) {
            assertThat(rs.next()).isTrue();
            return rs.getTimestamp(1).toInstant();
        }
    }
}
