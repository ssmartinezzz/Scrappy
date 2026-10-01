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
 * The role comes from the database, every request, uncached — Not from the token: a claim inside a
 * signed JWT cannot be withdrawn before it expires, so a user demoted from ADMIN would keep acting
 * as one for the rest of the token's life.
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
        // A request that fails to authenticate simply arrives at the authorization rules with no
        // subject, and the policy table decides — which is what keeps every "who may reach this"
        // answer in one place instead of two.
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
            // All three mean the same thing to a caller, and collapsing them removes a branch
            // somebody would otherwise have to remember.
            return Optional.empty();
        }

        Instant emitido = tokens.emitidoEn(header.substring(PREFIJO.length()).trim()).orElse(null);
        if (emitido != null && auth.get().passwordChangedAt() != null
                && emitido.isBefore(auth.get().passwordChangedAt()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
            return Optional.empty();
        }

        List<SimpleGrantedAuthority> roles = auth.get().roles().stream()
                .map(rol -> new SimpleGrantedAuthority("ROLE_" + rol))
                .toList();
        return Optional.of(new UsernamePasswordAuthenticationToken(
                new AuthenticatedSubject(subject.get(), auth.get().username()), null, roles));
    }
}
