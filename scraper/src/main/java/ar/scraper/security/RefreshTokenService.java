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
import org.apache.commons.lang3.StringUtils;

/**
 * Why the successor is cached in memory — The grace window has to hand back the same successor
 * pair, and that pair cannot be reconstructed from the database: refresh tokens are stored hashed,
 * so the successor's raw value ceases to exist anywhere the moment it is handed to the client.
 */
@Service
public class RefreshTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenService.class);

    public static final Duration VIDA = Duration.ofDays(14);

    /** How long a rotated token still replays its successor instead of counting as reuse. */
    public static final Duration GRACIA = Duration.ofSeconds(10);

    private static final int TOKEN_BYTES = 32;
    private static final int NONCE_BYTES = 32;

    private final RefreshTokenRepository repo;
    private final TokenService accessTokens;
    private final Clock reloj;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Replay> replays = new ConcurrentHashMap<>();

    public RefreshTokenService(RefreshTokenRepository repo, TokenService accessTokens, Clock reloj) {
        this.repo = repo;
        this.accessTokens = accessTokens;
        this.reloj = reloj;
    }

    /** The refresh token goes in a cookie, the nonce in the body. */
    public record Sesion(String refreshToken, String csrfNonce, UUID familyId, Instant expiraEn) {}

    public sealed interface Resultado permits Rotada, Replay, Rechazada, ReusoDetectado, CsrfInvalido {}

    public record Rotada(String accessToken, Sesion sesion) implements Resultado {}

    public record Replay(String accessToken, Sesion sesion, Instant vence) implements Resultado {}

    public record Rechazada(String motivo) implements Resultado {}

    /** A token presented after its grace window. */
    public record ReusoDetectado() implements Resultado {}

    /** The nonce did not match. Checked BEFORE the token is touched — see {@link #rotar}. */
    public record CsrfInvalido() implements Resultado {}

    public Sesion abrir(UUID usuarioId) {
        return emitir(usuarioId, UUID.randomUUID());
    }

    public Optional<Sesion> abrirSiCorresponde(UUID usuarioId, boolean esServicio) {
        return esServicio ? Optional.empty() : Optional.of(abrir(usuarioId));
    }

    public Resultado rotar(String rawToken, String nonce) {
        return rotar(rawToken, nonce, false);
    }

    /** The nonce is verified before the token is consumed, and the order is the whole point. */
    public Resultado rotar(String rawToken, String nonce, boolean bootstrapAdmitido) {
        if (StringUtils.isBlank(rawToken)) {
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
            // Running out is not evidence of anything.
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
            // ONLY this family's successors stop replaying. The comment already claimed family
            // scope; only the code disagreed.
            olvidarReplaysDe(fila.familyId());
            return new ReusoDetectado();
        }

        if (!repo.marcarRotado(fila.id(), ahora)) {
            return new Rechazada("ya rotado");
        }

        Sesion sucesor = emitir(fila.usuarioId(), fila.familyId());
        Rotada rotada = new Rotada(accessTokens.emitir(fila.usuarioId()), sucesor);
        replays.put(RefreshTokenRepository.hash(rawToken),
                new Replay(rotada.accessToken(), sucesor, ahora.plus(GRACIA)));
        return rotada;
    }

    /**
     * Revokes the whole family, so every device sharing this session is signed out — which is what
     * a user pressing "log out" means, and what makes it a real remedy after a suspected theft..
     */
    public boolean cerrar(String rawToken, String nonce) {
        if (StringUtils.isBlank(rawToken)) {
            return false;
        }
        Optional<RefreshTokenRepository.Fila> quiza = repo.buscar(rawToken);
        if (quiza.isEmpty()) {
            return false;
        }
        RefreshTokenRepository.Fila fila = quiza.get();
        // Logout has no bootstrap carve-out: a page with no nonce cannot log another session out
        // just by being on an allow-listed origin.
        if (!nonceCoincide(fila.csrfNonce(), nonce, false)) {
            return false;
        }
        repo.revocarFamilia(fila.familyId(), reloj.instant());
        // Scoped, for the same reason as the reuse branch: one user logging out must not evict
        // every other family's cached successor.
        olvidarReplaysDe(fila.familyId());
        return true;
    }

    /**
     * The cache is process-global and keyed by spent-token hash, so the only way to scope a wipe is
     * by the successor session each entry carries.
     */
    private void olvidarReplaysDe(UUID familyId) {
        replays.values().removeIf(replay -> replay.sesion().familyId().equals(familyId));
    }

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

    /** Constant-time, so a nonce cannot be recovered one character at a time. */
    private static boolean nonceCoincide(String esperado, String recibido, boolean bootstrapAdmitido) {
        if (esperado == null) {
            return true;
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
