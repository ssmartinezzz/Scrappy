package ar.scraper.db.support;

import ar.scraper.db.UsuarioRepository;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Seeds an account so a test has an owner to scope by.
 *
 * <p>Since slice 8 every personal read and write takes a {@code usuario_id}, so
 * a test touching favourites, saved outfits, feedback or dismissals needs a real
 * user row — the column is a foreign key, and a made-up UUID would be rejected.
 * One helper instead of the same six lines in a dozen test classes.</p>
 */
public final class UsuarioDePrueba {

    private UsuarioDePrueba() {
    }

    /** Creates the account if absent and returns its id. Idempotent within a test. */
    public static UUID crear(DataSource dataSource, String username) {
        return crear(dataSource, username, "VIEWER");
    }

    /**
     * Same, with an explicit role.
     *
     * <p>The role has to match whatever the test puts in the security context.
     * The database is the source of truth for authorization — the token carries
     * no role — so a fixture that says ADMIN in the context and writes VIEWER in
     * the table produces a subject the application correctly treats as a VIEWER,
     * and a test that fails for a reason that has nothing to do with what it
     * meant to check.</p>
     */
    public static UUID crear(DataSource dataSource, String username, String rol) {
        UsuarioRepository repo = new UsuarioRepository(dataSource);
        repo.crear(username, null, "$argon2id$de-prueba", false);
        repo.asignarRol(username, rol);
        return repo.buscarActivaPorUsername(username).orElseThrow().id();
    }

    /** The common case: one owner called {@code yo}. */
    public static UUID yo(DataSource dataSource) {
        return crear(dataSource, "yo");
    }
}
