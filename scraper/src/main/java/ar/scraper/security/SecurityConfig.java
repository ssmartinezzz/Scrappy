package ar.scraper.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The gate. One filter chain, one policy table, one terminator.
 *
 * <p><b>This is the slice where the API stops being open.</b> Everything before
 * it minted and rotated tokens that nothing checked. From here, a route not
 * named in {@link ApiRoutePolicy#TABLE} is refused rather than allowed.</p>
 *
 * <h3>{@code denyAll()} instead of a catch-all</h3>
 *
 * <p>The chain ends with {@code anyRequest().denyAll()} and there is deliberately
 * no {@code /** → authenticated} row above it. With one, the posture would be
 * authenticated-by-default, which is fine when every authenticated user has the
 * same rights and stops being fine the moment there are two roles: a new admin
 * endpoint nobody wrote a rule for would quietly be VIEWER-reachable. Failing
 * closed makes the omission a 403 during development rather than a hole in
 * production.</p>
 *
 * <h3>The {@code /error} forward has to be permitted, or every error becomes a 403</h3>
 *
 * <p>Boot forwards unhandled errors to {@code /error}. That forward is a request
 * too, it matches no policy row, and {@code denyAll()} blocks it — turning a
 * genuine 500 into a confusing 403 and hiding the original failure completely.
 * Permitting the {@code FORWARD} and {@code ERROR} dispatcher types costs
 * nothing: they cannot be triggered from outside, only by the container.</p>
 *
 * <h3>Spring's CSRF is off, and the refresh endpoint has its own</h3>
 *
 * <p>Every route here authenticates with a bearer header, which a cross-site
 * page cannot set — so there is no ambient credential for a CSRF token to
 * protect. Enabling the global machinery would impose a token dance on ~70
 * endpoints and break the CLI, to defend against something that cannot happen.
 * The one endpoint that <i>does</i> carry an ambient credential — the refresh
 * cookie — carries its own double-submit nonce, checked before the token is
 * consumed. See {@link RefreshTokenService#rotar}.</p>
 *
 * <h3>401 and 403 are different answers</h3>
 *
 * <p>Spring Security's default for an anonymous request to a protected route is
 * 403, which conflates two situations a client must handle differently: "I do
 * not know who you are" is fixed by authenticating, "I know who you are and the
 * answer is no" is not. A browser client seeing 403 on an expired token would
 * show a permissions error instead of refreshing, and the CLI's re-login on 401
 * would never fire. So the entry point below answers <b>401</b> for a missing or
 * unusable credential, and the access-denied handler answers <b>403</b> only for
 * a valid subject lacking the role.</p>
 *
 * <p>Sessions are stateless: identity comes from the token on every request, so
 * an {@code HttpSession} would be a second, divergent source of truth.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> {})   // delegates to CorsConfig's two ordered mappings
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint())
                        .accessDeniedHandler(accessDenied()))
                .formLogin(form -> form.disable())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll();

                    for (ApiRoutePolicy.RoutePolicy fila : ApiRoutePolicy.TABLE) {
                        String[] patrones = fila.patterns().toArray(String[]::new);
                        if (fila.cualquierMetodo()) {
                            aplicar(auth.requestMatchers(patrones), fila.access());
                        } else {
                            for (var metodo : fila.methods()) {
                                aplicar(auth.requestMatchers(metodo, patrones), fila.access());
                            }
                        }
                    }

                    // Reachable on purpose. See the class javadoc.
                    auth.anyRequest().denyAll();
                });
        return http.build();
    }

    /** No usable credential at all → 401, so a client knows to authenticate. */
    private static AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> responder(response, HttpServletResponse.SC_UNAUTHORIZED,
                "no_autenticado", "Falta un access token válido.");
    }

    /** A real subject without the role → 403. Authenticating again will not help. */
    private static AccessDeniedHandler accessDenied() {
        return (request, response, ex) -> responder(response, HttpServletResponse.SC_FORBIDDEN,
                "sin_permiso", "Tu cuenta no tiene permiso para esta operación.");
    }

    private static void responder(HttpServletResponse response, int status,
                                  String codigo, String mensaje) throws java.io.IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\":\"" + codigo + "\",\"mensaje\":\"" + mensaje + "\"}");
    }

    private static void aplicar(
            org.springframework.security.config.annotation.web.configurers
                    .AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizedUrl url,
            ApiRoutePolicy.Access access) {
        switch (access) {
            case PERMIT -> url.permitAll();
            case AUTHENTICATED -> url.authenticated();
            case ADMIN -> url.hasRole("ADMIN");
        }
    }
}
