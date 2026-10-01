package ar.scraper.security.reset;

import ar.scraper.db.PasswordResetRepository;
import ar.scraper.db.RefreshTokenRepository;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.apache.commons.lang3.StringUtils;

/**
 * "I forgot my password", from request to new password.
 *
 * <h3>The request thread does no account-dependent work — this is the design</h3>
 *
 * <p>{@link #solicitar} normalises the address, hands it to an executor, and
 * returns. The lookup, the rate limiter and the delivery attempt all happen
 * afterwards, on another thread. That is what makes the two branches
 * indistinguishable: an identical body and status is easy, but if the existing
 * account did a database read, a hash and an SMTP round-trip before responding,
 * the clock would answer the question the body refuses to. Both branches now
 * execute the same three statements before replying — normalise, submit,
 * return — so there is nothing left to measure.</p>
 *
 * <p>It also makes a delivery failure <b>structurally</b> unable to reach the
 * caller: by the time {@code send} throws, the 202 has already been written.</p>
 *
 * <h3>Service accounts cannot be reset, and not because of a check here</h3>
 *
 * <p>{@code V26} carries {@code CHECK (NOT es_servicio OR email IS NULL)}, so a
 * service account has no address, and a flow that looks accounts up <i>by</i>
 * address cannot find one. The exclusion is a property of the schema rather than
 * an {@code if} somebody can forget to write — which is exactly what the earlier
 * slice's constraint was for.</p>
 *
 * <h3>Confirming is one transaction</h3>
 *
 * <p>Consume the token, set the new hash and {@code password_changed_at}, revoke
 * every refresh family, void the other outstanding links. Any of those failing
 * alone would leave a mess with a plausible-looking front: a consumed token with
 * the old password still in place, or a new password with the intruder's session
 * still live.</p>
 */
@Service
public class PasswordResetService {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetService.class);

    /** Short on purpose: this is a live credential travelling through somebody's inbox. */
    public static final Duration VIDA_DEL_TOKEN = Duration.ofMinutes(30);

    private static final int TOKEN_BYTES = 32;

    private final UsuarioRepository usuarios;
    private final PasswordResetRepository tokens;
    private final RefreshTokenRepository refrescos;
    private final PasswordHasher hasher;
    private final PasswordResetChannel canal;
    private final ResetRateLimiter limiter;
    private final Clock reloj;
    private final Executor ejecutor;
    private final String baseDelEnlace;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PasswordResetService(UsuarioRepository usuarios,
                                PasswordResetRepository tokens,
                                RefreshTokenRepository refrescos,
                                PasswordHasher hasher,
                                PasswordResetChannel canal,
                                ResetRateLimiter limiter,
                                Clock reloj,
                                @Value("${password.reset.link-base}") String baseDelEnlace) {
        this(usuarios, tokens, refrescos, hasher, canal, limiter, reloj, baseDelEnlace,
                tarea -> Thread.ofVirtual().name("reset-dispatch").start(tarea));
    }

    /** Test seam: lets a test run the dispatch synchronously and await it. */
    PasswordResetService(UsuarioRepository usuarios,
                         PasswordResetRepository tokens,
                         RefreshTokenRepository refrescos,
                         PasswordHasher hasher,
                         PasswordResetChannel canal,
                         ResetRateLimiter limiter,
                         Clock reloj,
                         String baseDelEnlace,
                         Executor ejecutor) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.refrescos = refrescos;
        this.hasher = hasher;
        this.canal = canal;
        this.limiter = limiter;
        this.reloj = reloj;
        this.baseDelEnlace = baseDelEnlace;
        this.ejecutor = ejecutor;
    }

    /**
     * Accepts a reset request. Returns immediately and always, whatever the
     * address turns out to be.
     */
    public void solicitar(String direccion, String ip) {
        String normalizada = direccion == null ? "" : direccion.trim().toLowerCase();
        ejecutor.execute(() -> despachar(normalizada, ip));
    }

    /** Everything account-dependent lives here, off the request thread. */
    void despachar(String direccion, String ip) {
        try {
            if (!limiter.permitir(direccion, ip)) {
                // Silent by design: a 429 would answer "does this address exist?"
                // for anybody willing to ask twice.
                LOG.info("[RESET] pedido limitado por rate-limit");
                return;
            }
            Optional<UsuarioRepository.Cuenta> cuenta = usuarios.buscarActivaPorEmail(direccion);
            if (cuenta.isEmpty()) {
                return;
            }
            String token = aleatorio();
            tokens.crear(cuenta.get().id(), token, reloj.instant().plus(VIDA_DEL_TOKEN));
            canal.enviar(direccion, enlaceDe(token));
        } catch (Exception e) {
            // Nothing here can reach the caller — the response was written long
            // ago — so a failure that stayed silent would be invisible forever.
            LOG.error("[RESET] falló el despacho del reseteo: {}", e.getMessage(), e);
        }
    }

    /**
     * The token rides in the URL <b>fragment</b>, not the query string.
     *
     * <p>A fragment is never sent to a server, so it cannot land in an access
     * log, and it is never included in a {@code Referer}, so it cannot leak to
     * whatever third-party resource the page loads. A live credential in a query
     * string does both.</p>
     */
    private String enlaceDe(String token) {
        return baseDelEnlace.replaceAll("/+$", "") + "/reset-password#token=" + token;
    }

    /**
     * Consumes the token and changes the password, or reports failure.
     *
     * <p>The {@code false} branches mark the transaction rollback-only: a token consumed by a
     * call that then fails to change the password must not stay consumed.</p>
     *
     * @return {@code false} for an unknown, expired or already-used token, and
     *         for a password that fails the length check. The caller must not
     *         tell them apart either.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmar(String token, String nuevaPassword) {
        if (StringUtils.isBlank(token) || nuevaPassword == null || nuevaPassword.length() < 8) {
            return false;
        }
        Instant ahora = reloj.instant();
        String hash = hasher.hash(nuevaPassword);

        Optional<UUID> duenio = tokens.consumir(token, ahora);
        if (duenio.isEmpty()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        UUID usuarioId = duenio.get();
        if (!usuarios.cambiarPassword(usuarioId, hash, ahora)) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        refrescos.revocarTodasLasDe(usuarioId, ahora);
        tokens.anularPendientesDe(usuarioId, ahora);
        LOG.info("[RESET] contraseña cambiada y sesiones revocadas para el usuario {}", usuarioId);
        return true;
    }

    private String aleatorio() {
        byte[] buffer = new byte[TOKEN_BYTES];
        random.nextBytes(buffer);
        // Opaque and high-entropy, deliberately not a JWT: it has to be checked
        // against the revocation table on every use, so a self-describing token
        // would carry claims nobody is allowed to trust anyway.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
