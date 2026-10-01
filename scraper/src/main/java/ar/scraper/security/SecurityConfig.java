package ar.scraper.security;

import ar.scraper.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The gate: one filter chain, one policy table, one terminator. A route not named in
 * {@link ApiRoutePolicy#TABLE} is refused.
 *
 * <p>{@code denyAll()} rather than a catch-all {@code /** -> authenticated}: with two roles, an admin
 * endpoint nobody wrote a rule for would quietly be VIEWER-reachable. Failing closed makes the omission a
 * 403 in development instead of a hole in production.</p>
 *
 * <p>The {@code /error} forward must be permitted, or every error becomes a 403: Boot forwards unhandled
 * errors to {@code /error}, which matches no policy row. FORWARD/ERROR dispatches cannot be triggered
 * from outside, only by the container. ASYNC is permitted for the same reason: when an SSE emitter
 * completes or times out the container re-dispatches the request, and the stateless chain has no
 * subject to re-authorize it with — the request was already authorized on its first dispatch.</p>
 *
 * <p>Spring's CSRF is off: every route authenticates with a bearer header, which a cross-site page cannot
 * set. The one endpoint with an ambient credential, the refresh cookie, carries its own double-submit
 * nonce ({@link RefreshTokenService#rotar}).</p>
 *
 * <p>401 and 403 differ: the entry point answers 401 for a missing/unusable credential (the client should
 * authenticate or refresh, and the CLI re-logs in on 401), the access-denied handler answers 403 only for
 * a valid subject lacking the role. Sessions are stateless.</p>
 *
 * <p>Both answer with the {@link ApiError} envelope, like every other error.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                    auth.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR, DispatcherType.ASYNC).permitAll();

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

                    auth.anyRequest().denyAll();
                });
        return http.build();
    }


    private static AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> responder(response, HttpServletResponse.SC_UNAUTHORIZED,
                "no_autenticado", "Falta un access token válido.");
    }


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
        MAPPER.writeValue(response.getWriter(), ApiError.of(codigo, mensaje));
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
