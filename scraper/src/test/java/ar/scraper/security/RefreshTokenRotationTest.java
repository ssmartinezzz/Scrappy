package ar.scraper.security;

import ar.scraper.db.RefreshTokenRepository;
import ar.scraper.db.UsuarioRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 4 — refresh-token rotation and its reuse
 * detector.
 *
 * <p>Rotation is what makes a fourteen-day credential tolerable: each refresh
 * burns the token that was presented, so a stolen one is only useful until its
 * rightful owner next refreshes. The detector is the other half — the moment a
 * token is presented twice, one of the two presenters is not the owner, and
 * since we cannot tell which, the safe move is to invalidate the whole family
 * and make both re-authenticate.</p>
 *
 * <p><b>The grace window is the awkward part, and it is awkward for a concrete
 * reason.</b> A client firing several requests at once gets several 401s and
 * may refresh more than once with the same token — tripping our own detector
 * against ourselves and logging the user out for being fast. The design's answer
 * is to replay the successor pair for ten seconds instead of crying theft. That
 * cannot be reconstructed from the database: tokens are stored hashed, so the
 * successor's raw value no longer exists anywhere once it has been handed out.
 * It therefore lives in a short-lived in-memory cache, which also means it is
 * per-instance — acceptable here because this application is single-instance by
 * construction, and stated rather than discovered later.</p>
 *
 * <p><b>Ten seconds is a proposal, not a measurement.</b> It cannot be measured
 * without a browser client, and none exists yet.</p>
 */
@Epic("Security")
@Feature("Refresh tokens")
@Story("Rotation, reuse detection, and the grace window")
@DisplayName("RefreshTokenService — rotation and reuse")
class RefreshTokenRotationTest extends PostgresTestBase {

    private static final Instant T0 = Instant.parse("2026-08-19T12:00:00Z");
    private static final String SECRETO = "un-secreto-de-al-menos-32-bytes-para-hs256";

    private final MutableClock reloj = new MutableClock(T0);

    private UsuarioRepository usuarios;
    private RefreshTokenRepository refrescos;
    private RefreshTokenService service;
    private UUID ana;

    @BeforeEach
    void setUp() {
        usuarios = new UsuarioRepository(dataSource());
        refrescos = new RefreshTokenRepository(dataSource());
        service = new RefreshTokenService(refrescos, new TokenService(SECRETO, reloj), reloj);

        usuarios.crear("ana", null, "$argon2id$x", false);
        ana = usuarios.buscarActivaPorUsername("ana").orElseThrow().id();
    }

    // ── 4.1 · a valid token rotates ──────────────────────────────────────────

    @Test
    @DisplayName("a valid unused token rotates: new pair, same family, old one marked rotated")
    void aValidTokenRotates() throws Exception {
        RefreshTokenService.Sesion inicial = service.abrir(ana);

        RefreshTokenService.Resultado resultado = service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        assertThat(resultado).isInstanceOf(RefreshTokenService.Rotada.class);
        RefreshTokenService.Rotada ok = (RefreshTokenService.Rotada) resultado;
        assertThat(ok.accessToken()).isNotBlank();
        assertThat(ok.sesion().refreshToken())
                .as("the presented token is spent — a rotation that returns the same value rotates nothing")
                .isNotEqualTo(inicial.refreshToken());
        assertThat(ok.sesion().familyId())
                .as("the family is the thread that ties a session together across rotations")
                .isEqualTo(inicial.familyId());
        assertThat(rotadoEn(inicial.refreshToken())).isNotNull();
    }

    @Test
    @DisplayName("the new nonce differs from the old one")
    void rotationIssuesAFreshNonce() {
        RefreshTokenService.Sesion inicial = service.abrir(ana);

        RefreshTokenService.Rotada ok =
                (RefreshTokenService.Rotada) service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        assertThat(ok.sesion().csrfNonce()).isNotEqualTo(inicial.csrfNonce());
    }

    // ── 4.2 · never stored in the clear ──────────────────────────────────────

    @Test
    @DisplayName("no row ever holds a raw token")
    void tokensAreOnlyEverStoredHashed() throws Exception {
        RefreshTokenService.Sesion inicial = service.abrir(ana);
        service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        List<String> hashes = todosLosHashes();
        assertThat(hashes).hasSize(2);
        assertThat(hashes)
                .as("a stolen database dump must not be a stack of working refresh tokens")
                .doesNotContain(inicial.refreshToken());
        assertThat(hashes).allSatisfy(h -> assertThat(h).matches("[0-9a-f]{64}"));
    }

    // ── 4.4 · reuse, and the grace window ────────────────────────────────────

    @Test
    @DisplayName("reuse past the grace window invalidates the whole family")
    void reuseAfterTheGraceWindowBurnsTheFamily() throws Exception {
        RefreshTokenService.Sesion inicial = service.abrir(ana);
        RefreshTokenService.Rotada primera =
                (RefreshTokenService.Rotada) service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        reloj.avanzar(RefreshTokenService.GRACIA.plusSeconds(1));
        RefreshTokenService.Resultado segunda = service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        assertThat(segunda).isInstanceOf(RefreshTokenService.ReusoDetectado.class);
        assertThat(revocadosEnLaFamilia(inicial.familyId()))
                .as("we cannot tell which of the two presenters is the thief, so neither keeps the session")
                .isEqualTo(2);

        assertThat(service.rotar(primera.sesion().refreshToken(), primera.sesion().csrfNonce()))
                .as("the successor is burnt too — that is what 'the whole family' means")
                .isInstanceOf(RefreshTokenService.Rechazada.class);
    }

    @Test
    @DisplayName("reuse inside the grace window replays the successor instead of crying theft")
    void reuseInsideTheGraceWindowReplays() {
        RefreshTokenService.Sesion inicial = service.abrir(ana);
        RefreshTokenService.Rotada primera =
                (RefreshTokenService.Rotada) service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        reloj.avanzar(java.time.Duration.ofSeconds(2));
        RefreshTokenService.Resultado segunda = service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        assertThat(segunda)
                .as("parallel requests are not an attack, and logging the user out for being fast is a bug")
                .isInstanceOf(RefreshTokenService.Replay.class);
        RefreshTokenService.Replay replay = (RefreshTokenService.Replay) segunda;
        assertThat(replay.sesion().refreshToken())
                .as("the same successor is handed back, not a third token")
                .isEqualTo(primera.sesion().refreshToken());
    }

    @Test
    @DisplayName("a replay does not rotate again")
    void aReplayCreatesNoNewRow() throws Exception {
        RefreshTokenService.Sesion inicial = service.abrir(ana);
        service.rotar(inicial.refreshToken(), inicial.csrfNonce());
        int filasAntes = contarFilas();

        reloj.avanzar(java.time.Duration.ofSeconds(2));
        service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        assertThat(contarFilas()).isEqualTo(filasAntes);
    }

    // ── expiry is not theft ──────────────────────────────────────────────────

    @Test
    @DisplayName("an expired token is rejected without touching the family")
    void expiryIsNotTheft() throws Exception {
        RefreshTokenService.Sesion inicial = service.abrir(ana);

        reloj.avanzar(RefreshTokenService.VIDA.plusSeconds(1));
        RefreshTokenService.Resultado resultado = service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        assertThat(resultado).isInstanceOf(RefreshTokenService.Rechazada.class);
        assertThat(revocadosEnLaFamilia(inicial.familyId()))
                .as("a token that simply ran out is not evidence of anything")
                .isZero();
    }

    @Test
    @DisplayName("an unknown token is rejected and reveals nothing")
    void anUnknownTokenIsRejected() {
        assertThat(service.rotar("un-token-que-nunca-existio", "nonce"))
                .isInstanceOf(RefreshTokenService.Rechazada.class);
    }

    // ── logout ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("closing a session revokes the whole family")
    void closingRevokesTheFamily() throws Exception {
        RefreshTokenService.Sesion inicial = service.abrir(ana);
        RefreshTokenService.Rotada primera =
                (RefreshTokenService.Rotada) service.rotar(inicial.refreshToken(), inicial.csrfNonce());

        assertThat(service.cerrar(primera.sesion().refreshToken(), primera.sesion().csrfNonce())).isTrue();

        assertThat(service.rotar(primera.sesion().refreshToken(), primera.sesion().csrfNonce()))
                .isInstanceOf(RefreshTokenService.Rechazada.class);
        assertThat(revocadosEnLaFamilia(inicial.familyId())).isEqualTo(2);
    }

    @Test
    @DisplayName("a service account is never given a refresh token")
    void serviceAccountsGetNoSession() {
        usuarios.crear("cli", null, "$argon2id$x", true);
        UUID cli = usuarios.buscarActivaPorUsername("cli").orElseThrow().id();

        assertThat(service.abrirSiCorresponde(cli, true))
                .as("the CLI re-authenticates from .env; a rotating session it never uses is "
                        + "a credential lying around for nothing")
                .isEmpty();
    }

    // ── frontend-auth-ui, Phase 2 · bootstrapAdmitido ────────────────────────

    @Test
    @DisplayName("2.1 — bootstrapAdmitido=true admits a nonce-less refresh when the row has a stored nonce")
    void bootstrapAdmitidoTrueAdmitsANonceLessRefresh() {
        RefreshTokenService.Sesion inicial = service.abrir(ana);

        RefreshTokenService.Resultado resultado = service.rotar(inicial.refreshToken(), null, true);

        assertThat(resultado)
                .as("the row has a real stored nonce (emitir() always mints one) and the caller "
                        + "vouched for the request via bootstrapAdmitido — this is the cold-start path")
                .isInstanceOf(RefreshTokenService.Rotada.class);
    }

    @Test
    @DisplayName("2.2 — bootstrapAdmitido=false still refuses a nonce-less refresh, token intact")
    void bootstrapAdmitidoFalseStillRefusesANonceLessRefresh() {
        RefreshTokenService.Sesion inicial = service.abrir(ana);

        RefreshTokenService.Resultado resultado = service.rotar(inicial.refreshToken(), null, false);

        assertThat(resultado).isInstanceOf(RefreshTokenService.CsrfInvalido.class);
        assertThat(service.rotar(inicial.refreshToken(), inicial.csrfNonce(), false))
                .as("a rejected bootstrap attempt must not have consumed the token")
                .isInstanceOf(RefreshTokenService.Rotada.class);
    }

    @Test
    @DisplayName("2.3 — bootstrapAdmitido=true does not disturb the pre-existing null-stored-nonce path")
    void bootstrapAdmitidoTrueLeavesTheNullStoredNoncePathUntouched() throws Exception {
        RefreshTokenService.Sesion inicial = service.abrir(ana);
        limpiarNonceAlmacenado(inicial.refreshToken());

        RefreshTokenService.Resultado resultado = service.rotar(inicial.refreshToken(), "cualquier-cosa", true);

        assertThat(resultado)
                .as("esperado == null is unconditional — bootstrapAdmitido adds a path, it does not "
                        + "replace this one")
                .isInstanceOf(RefreshTokenService.Rotada.class);
    }

    @Test
    @DisplayName("2.8 — a wrong, non-null nonce is always 403, regardless of bootstrapAdmitido")
    void aWrongNonNullNonceIsAlwaysRejectedRegardlessOfBootstrapAdmitido() {
        RefreshTokenService.Sesion inicial = service.abrir(ana);

        RefreshTokenService.Resultado resultado =
                service.rotar(inicial.refreshToken(), "nonce-equivocado", true);

        assertThat(resultado)
                .as("the nonce-less bootstrap path grants no other leniency — a present-but-wrong "
                        + "nonce is a separate case, never admitted by this flag")
                .isInstanceOf(RefreshTokenService.CsrfInvalido.class);
        assertThat(service.rotar(inicial.refreshToken(), inicial.csrfNonce(), false))
                .as("and the token was not consumed by the rejected attempt")
                .isInstanceOf(RefreshTokenService.Rotada.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void limpiarNonceAlmacenado(String rawToken) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("UPDATE refresh_token SET csrf_nonce = NULL WHERE token_hash = '"
                    + RefreshTokenRepository.hash(rawToken) + "'");
        }
    }

    private java.sql.Timestamp rotadoEn(String rawToken) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT rotated_at FROM refresh_token WHERE token_hash = '"
                             + RefreshTokenRepository.hash(rawToken) + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getTimestamp(1);
        }
    }

    private List<String> todosLosHashes() throws Exception {
        List<String> hashes = new ArrayList<>();
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT token_hash FROM refresh_token")) {
            while (rs.next()) {
                hashes.add(rs.getString(1));
            }
        }
        return hashes;
    }

    private int revocadosEnLaFamilia(UUID familyId) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM refresh_token WHERE family_id = '" + familyId
                             + "' AND revoked_at IS NOT NULL")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private int contarFilas() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM refresh_token")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    /** A clock the test moves by hand — sleeping for ten seconds is not a test. */
    static final class MutableClock extends Clock {
        private Instant ahora;

        MutableClock(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(java.time.Duration cuanto) {
            ahora = ahora.plus(cuanto);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }
}
