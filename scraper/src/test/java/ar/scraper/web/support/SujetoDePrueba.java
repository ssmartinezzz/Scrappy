package ar.scraper.web.support;

import ar.scraper.security.AuthenticatedSubject;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * Puts an authenticated subject in the security context for a controller test.
 *
 * <p>Since slice 8 the personal endpoints resolve their owner through
 * {@code ActorResolver}, which reads the security context. A unit test that
 * calls those endpoints directly has no request behind it, so without this the
 * subject is absent and the endpoint refuses — correctly, but the test would be
 * describing the anonymous case rather than the one it means to.</p>
 *
 * <p>Sets a real subject rather than stubbing {@code ActorResolver}: the point is
 * to exercise the same path production takes, and a stub would hide a change in
 * how the subject is read.</p>
 */
public final class SujetoDePrueba {

    private SujetoDePrueba() {
    }

    /**
     * For a test backed by a real database.
     *
     * <p>The random-id overload is fine when the repository is mocked, and wrong
     * when it is not: {@code usuario_id} is a foreign key, so an id nobody owns
     * makes every personal write fail — and the repositories log and swallow, so
     * it fails as "nothing was saved" rather than as an error. Seeding a real row
     * is the difference between a test that exercises the scoping and one that
     * quietly exercises nothing.</p>
     */
    public static UUID entrar(DataSource dataSource, String rol) {
        UUID id = ar.scraper.db.support.UsuarioDePrueba.crear(dataSource, "test-" + rol.toLowerCase(), rol);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedSubject(id, "test-" + rol.toLowerCase()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + rol))));
        return id;
    }

    /** For a test whose repository is mocked: no row has to exist. */
    public static UUID entrar(String rol) {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedSubject(id, "test-" + rol.toLowerCase()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + rol))));
        return id;
    }

    /** Must run after every test: the context is a thread-local and leaks between them. */
    public static void salir() {
        SecurityContextHolder.clearContext();
    }
}
