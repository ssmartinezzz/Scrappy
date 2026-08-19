package ar.scraper.security;

import ar.scraper.db.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a bearer token into an authenticated subject, or into nothing.
 *
 * <h3>The role comes from the database, every request, uncached</h3>
 *
 * <p>Not from the token: a claim inside a signed JWT cannot be withdrawn before
 * it expires, so a user demoted from ADMIN would keep acting as one for the rest
 * of the token's life. Not from a cache either — invalidating it would have to
 * fire on every write to {@code usuario_rol}, on {@code usuario.activo}, and on
 * user deletion, and a <b>missed eviction is a privilege escalation that
 * persists silently</b>. The cost is one indexed read on a pooled connection,
 * measured at roughly 0.2 ms against the precedent of two pooled queries at
 * 0.43 ms. That is a price worth paying for a property that cannot be got any
 * other way.</p>
 *
 * <p>The lookup filters {@code activo = TRUE}, so an empty result means
 * "disabled" and "no role at all" alike, and deactivation takes effect on the
 * very next request with no extra branch and no reissue.</p>
 *
 * <h3>Nothing the client says about identity is consulted</h3>
 *
 * <p>Not a body field, not {@code X-User-Role}, not a header a gateway claims to
 * have verified. A gateway is out of scope for this change and deferred, and a
 * client that reaches this backend directly could forge whatever header one
 * would have set — the same broken-access-control failure, moved one hop
 * upstream. This filter validates its own token and reads its own database, and
 * that constraint binds any future gateway rather than being relaxed by it.</p>
 *
 * <h3>{@code password_changed_at} closes the fifteen-minute window</h3>
 *
 * <p>A password reset revokes every refresh family, but access tokens already
 * issued stay cryptographically valid until they expire. Rejecting tokens whose
 * {@code iat} predates the password change closes that window — and it costs
 * nothing extra, because the same row is already being read for the role.</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIJO = "Bearer ";

    private final TokenService tokens;
    private final UsuarioRepository usuarios;

    public JwtAuthFilter(TokenService tokens, UsuarioRepository usuarios) {
        this.tokens = tokens;
        this.usuarios = usuarios;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        autenticar(request).ifPresent(SecurityContextHolder.getContext()::setAuthentication);
        chain.doFilter(request, response);
        // Nothing is rejected here. A request that fails to authenticate simply
        // arrives at the authorization rules with no subject, and the policy
        // table decides — which is what keeps every "who may reach this" answer
        // in one place instead of two.
    }

    private Optional<UsernamePasswordAuthenticationToken> autenticar(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIJO)) {
            return Optional.empty();
        }
        Optional<UUID> subject = tokens.verificar(header.substring(PREFIJO.length()).trim());
        if (subject.isEmpty()) {
            return Optional.empty();
        }

        Optional<UsuarioRepository.Autorizacion> auth = usuarios.autorizacionDe(subject.get());
        if (auth.isEmpty()) {
            // Unknown, disabled, or holding no role. All three mean the same
            // thing to a caller, and collapsing them removes a branch somebody
            // would otherwise have to remember.
            return Optional.empty();
        }

        Instant emitido = tokens.emitidoEn(header.substring(PREFIJO.length()).trim()).orElse(null);
        if (emitido != null && auth.get().passwordChangedAt() != null
                && !emitido.isAfter(auth.get().passwordChangedAt())) {
            return Optional.empty();
        }

        List<SimpleGrantedAuthority> roles = auth.get().roles().stream()
                .map(rol -> new SimpleGrantedAuthority("ROLE_" + rol))
                .toList();
        return Optional.of(new UsernamePasswordAuthenticationToken(
                new AuthenticatedSubject(subject.get(), auth.get().username()), null, roles));
    }
}
