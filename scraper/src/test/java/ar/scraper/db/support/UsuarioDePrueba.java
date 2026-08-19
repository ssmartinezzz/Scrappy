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
        UsuarioRepository repo = new UsuarioRepository(dataSource);
        repo.crear(username, null, "$argon2id$de-prueba", false);
        repo.asignarRol(username, "VIEWER");
        return repo.buscarActivaPorUsername(username).orElseThrow().id();
    }

    /** The common case: one owner called {@code yo}. */
    public static UUID yo(DataSource dataSource) {
        return crear(dataSource, "yo");
    }
}
