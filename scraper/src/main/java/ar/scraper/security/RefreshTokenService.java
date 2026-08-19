package ar.scraper.security;

import ar.scraper.db.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The refresh-token state machine: mint, rotate, detect reuse, close.
 *
 * <pre>
 *   abrir ──► ACTIVE ──present(valid)──► rotate: new access + successor refresh
 *                │                       (same family)
 *                ▼ rotated_at set
 *            ROTATED ──present again──┬── within grace ──► replay the successor
 *                │                    └── after grace  ──► REUSE: burn the family
 *                ▼
 *            EXPIRED ──► rejected, family untouched (running out is not theft)
 * </pre>
 *
 * <h3>Why the successor is cached in memory</h3>
 *
 * <p>The grace window has to hand back the <i>same</i> successor pair, and that
 * pair cannot be reconstructed from the database: refresh tokens are stored
 * hashed, so the successor's raw value ceases to exist anywhere the moment it
 * is handed to the client. The only place it can come from is a short-lived
 * cache, which is what {@link #replays} is.</p>
 *
 * <p>Two consequences worth stating rather than discovering: the cache is
 * <b>per-instance</b>, so a second backend would not replay a sibling's
 * rotation — acceptable because this application is single-instance by
 * construction — and it holds a live refresh token in memory for the length of
 * the window, which is why the window is short and why entries are dropped as
 * soon as they age out.</p>
 *
 * <h3>Why a grace window exists at all</h3>
 *
 * <p>A client that fires several requests at once gets several 401s and may
 * refresh more than once with the same token. Without the window that is
 * indistinguishable from theft, and the detector would log the user out for
 * being fast — turning a blocked attack into a self-inflicted one. Ten seconds
 * is a <b>proposal, not a measurement</b>: measuring it needs a browser client,
 * and none exists yet.</p>
 */
@Service
public class RefreshTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenService.class);

    /** Refresh-token lifetime. */
    public static final Duration VIDA = Duration.ofDays(14);

    /** How long a rotated token still replays its successor instead of counting as reuse. */
    public static final Duration GRACIA = Duration.ofSeconds(10);

    private static final int TOKEN_BYTES = 32;   // 256 bits
    private static final int NONCE_BYTES = 32;

    private final RefreshTokenRepository repo;
    private final TokenService accessTokens;
    private final Clock reloj;
    private final SecureRandom random = new SecureRandom();

    /** hash of the spent token → what to hand back if it is presented again inside the window. */
    private final Map<String, Replay> replays = new ConcurrentHashMap<>();

    public RefreshTokenService(RefreshTokenRepository repo, TokenService accessTokens, Clock reloj) {
        this.repo = repo;
        this.accessTokens = accessTokens;
        this.reloj = reloj;
    }

    /** A live session's client-side half. The refresh token goes in a cookie, the nonce in the body. */
    public record Sesion(String refreshToken, String csrfNonce, UUID familyId, Instant expiraEn) {}

    public sealed interface Resultado permits Rotada, Replay, Rechazada, ReusoDetectado, CsrfInvalido {}

    /** The normal path: the presented token was spent and a successor issued. */
    public record Rotada(String accessToken, Sesion sesion) implements Resultado {}

    /** Inside the grace window: the successor is handed back again, unchanged. */
    public record Replay(String accessToken, Sesion sesion, Instant vence) implements Resultado {}

    /** Unknown, expired or already-revoked. Says nothing about which — the caller has no use for it. */
    public record Rechazada(String motivo) implements Resultado {}

    /** A token presented after its grace window. The family is gone. */
    public record ReusoDetectado() implements Resultado {}

    /** The nonce did not match. Checked BEFORE the token is touched — see {@link #rotar}. */
    public record CsrfInvalido() implements Resultado {}

    /**
     * Opens a session. Callers that may be dealing with a service account should
     * use {@link #abrirSiCorresponde} instead.
     */
    public Sesion abrir(UUID usuarioId) {
        return emitir(usuarioId, UUID.randomUUID());
    }

    /**
     * @return empty for a service account. The CLI re-authenticates from its own
     *         {@code .env} on a 401 and never presents a refresh token, so
     *         minting one would leave a fourteen-day credential lying around for
     *         a client that has no use for it.
     */
    public Optional<Sesion> abrirSiCorresponde(UUID usuarioId, boolean esServicio) {
        return esServicio ? Optional.empty() : Optional.of(abrir(usuarioId));
    }

    /**
     * Rotates, replays, or refuses. Equivalent to {@link #rotar(String, String, boolean)}
     * with {@code bootstrapAdmitido=false} — every pre-frontend-auth-ui caller,
     * including this application's own {@code AuthEndpoints}, passes through here
     * with no bootstrap leniency at all.
     */
    public Resultado rotar(String rawToken, String nonce) {
        return rotar(rawToken, nonce, false);
    }

    /**
     * Rotates, replays, or refuses.
     *
     * <p><b>The nonce is verified before the token is consumed, and the order is
     * the whole point.</b> Reversed, a forged cross-site request would rotate
     * first and fail the CSRF check afterwards — leaving the real client holding
     * a token that has just been spent, so its next refresh trips reuse
     * detection. That converts a blocked attack into a forced logout, which is
     * most of what the attacker wanted anyway.</p>
     *
     * <h3>{@code bootstrapAdmitido} — the frontend-auth-ui, Phase 2 carve-out</h3>
     *
     * <p>A cold page load has no nonce to present at all: it is not "wrong", it
     * is simply absent. {@code AuthEndpoints} decides, from the servlet request's
     * {@code Origin} and {@code Sec-Fetch-Site} headers (never from anything
     * inside this service), whether that absence should be forgiven, and passes
     * the verdict in as this boolean. {@code rotar} stays a pure function of its
     * arguments — no static, no thread-local — so the decision is always visible
     * at the call site. A nonce that is <b>present but wrong</b> is never
     * forgiven by this flag; see {@link #nonceCoincide}.</p>
     */
    public Resultado rotar(String rawToken, String nonce, boolean bootstrapAdmitido) {
        if (rawToken == null || rawToken.isBlank()) {
            return new Rechazada("sin token");
        }

        Instant ahora = reloj.instant();
        purgarReplaysVencidos(ahora);

        Optional<RefreshTokenRepository.Fila> quiza = repo.buscar(rawToken);
        if (quiza.isEmpty()) {
            return new Rechazada("desconocido");
        }
        RefreshTokenRepository.Fila fila = quiza.get();

        if (!nonceCoincide(fila.csrfNonce(), nonce, bootstrapAdmitido)) {
            return new CsrfInvalido();
        }

        if (fila.revocado()) {
            return new Rechazada("revocado");
        }
        if (!ahora.isBefore(fila.expiresAt())) {
            // Running out is not evidence of anything. The family is left alone.
            return new Rechazada("vencido");
        }

        if (fila.rotado()) {
            Replay replay = replays.get(RefreshTokenRepository.hash(rawToken));
            if (replay != null && ahora.isBefore(replay.vence())) {
                return replay;
            }
            LOG.warn("[AUTH] refresh token reusado fuera de la ventana de gracia — se revoca la familia {}",
                    fila.familyId());
            repo.revocarFamilia(fila.familyId(), ahora);
            replays.keySet().removeIf(hash -> true); // the family's successors must not replay either
            return new ReusoDetectado();
        }

        if (!repo.marcarRotado(fila.id(), ahora)) {
            // Somebody else rotated it between the read and here. Theirs is the
            // real rotation; this call is the second presenter.
            return new Rechazada("ya rotado");
        }

        Sesion sucesor = emitir(fila.usuarioId(), fila.familyId());
        Rotada rotada = new Rotada(accessTokens.emitir(fila.usuarioId()), sucesor);
        replays.put(RefreshTokenRepository.hash(rawToken),
                new Replay(rotada.accessToken(), sucesor, ahora.plus(GRACIA)));
        return rotada;
    }

    /**
     * Logout. Revokes the whole family, so every device sharing this session is
     * signed out — which is what a user pressing "log out" means, and what makes
     * it a real remedy after a suspected theft.
     *
     * @return {@code false} when the token is unknown or the nonce does not match.
     */
    public boolean cerrar(String rawToken, String nonce) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        Optional<RefreshTokenRepository.Fila> quiza = repo.buscar(rawToken);
        if (quiza.isEmpty()) {
            return false;
        }
        RefreshTokenRepository.Fila fila = quiza.get();
        // Logout has no bootstrap carve-out: a page with no nonce cannot log
        // another session out just by being on an allow-listed origin.
        if (!nonceCoincide(fila.csrfNonce(), nonce, false)) {
            return false;
        }
        repo.revocarFamilia(fila.familyId(), reloj.instant());
        replays.clear();
        return true;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Sesion emitir(UUID usuarioId, UUID familyId) {
        String token = aleatorio(TOKEN_BYTES);
        String nonce = aleatorio(NONCE_BYTES);
        Instant vence = reloj.instant().plus(VIDA);
        repo.crear(usuarioId, token, familyId, nonce, vence);
        return new Sesion(token, nonce, familyId, vence);
    }

    private String aleatorio(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /**
     * Constant-time, so a nonce cannot be recovered one character at a time.
     *
     * <p>Three cases, and only one of them consults {@code bootstrapAdmitido}:
     * a row with no stored nonce at all is unaffected (nothing to compare
     * against, exactly as before this parameter existed); a nonce-less request
     * against a row that <b>does</b> have a stored nonce is admitted only when
     * the caller vouched for it; a <b>present but wrong</b> nonce is never
     * admitted by this flag — {@code bootstrapAdmitido} widens what counts as
     * "no nonce presented", not what counts as "the right nonce".</p>
     */
    private static boolean nonceCoincide(String esperado, String recibido, boolean bootstrapAdmitido) {
        if (esperado == null) {
            return true; // pre-nonce row; nothing to compare against
        }
        if (recibido == null) {
            return bootstrapAdmitido;
        }
        return java.security.MessageDigest.isEqual(
                esperado.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                recibido.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void purgarReplaysVencidos(Instant ahora) {
        replays.values().removeIf(replay -> !ahora.isBefore(replay.vence()));
    }
}
