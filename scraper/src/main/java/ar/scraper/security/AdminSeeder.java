package ar.scraper.security;

import ar.scraper.db.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates the bootstrap admin and the CLI service account at startup, then
 * adopts every personal row that has no owner yet.
 *
 * <p><b>Why an {@link ApplicationRunner} and not a migration.</b> Flyway runs
 * {@code V26} during startup, and a migration is byte-frozen the moment it is
 * applied — a password written there could never be removed, in a public
 * repository. The account therefore has to be created by code that reads the
 * environment, and an {@code ApplicationRunner} is the only hook guaranteed to
 * run after both the migration and a full context refresh. {@code MarcaSeeder}
 * already establishes the pattern in this codebase.</p>
 *
 * <p><b>Why seeding and adoption share one transaction.</b> Adoption points
 * every ownerless row at the admin's id. If the seed were rolled back
 * afterwards, those rows would reference a user that does not exist — a
 * dangling owner, which is strictly worse than no owner, because the rows
 * become unreachable instead of merely unclaimed.</p>
 *
 * <p><b>Both operations are idempotent</b>, which is what makes restarting and
 * running two instances at once safe: the insert is
 * {@code ON CONFLICT DO NOTHING} and adoption is scoped to
 * {@code usuario_id IS NULL}, so the second runner finds nothing to do rather
 * than fighting the first.</p>
 *
 * <p><b>An existing password hash is never overwritten.</b> This runs on every
 * boot, so overwriting would quietly reset the admin password to the
 * environment value each restart — turning the variable into a permanent back
 * door instead of an initial value. The consequence is worth stating plainly:
 * <b>changing {@code ADMIN_BOOTSTRAP_PASSWORD} and restarting does not change
 * the password of an account that already exists.</b> Recovery is direct SQL.</p>
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSeeder.class);

    /**
     * The literal value shipped in {@code .env.example}. Refusing it closes the
     * likeliest path to a world-known admin password: copying the example file
     * and never editing it.
     */
    public static final String PLACEHOLDER = "cambiame-por-una-password-real";

    private static final String ROL_ADMIN = "ADMIN";

    private final UsuarioRepository usuarios;
    private final PasswordHasher hasher;
    private final String adminUsername;
    private final String adminPassword;
    private final String servicioUsername;
    private final String servicioPassword;

    public AdminSeeder(UsuarioRepository usuarios,
                       PasswordHasher hasher,
                       @Value("${admin.bootstrap.username}") String adminUsername,
                       @Value("${admin.bootstrap.password}") String adminPassword,
                       @Value("${cli.service-account.username}") String servicioUsername,
                       @Value("${cli.service-account.password}") String servicioPassword) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.servicioUsername = servicioUsername;
        this.servicioPassword = servicioPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Both passwords are checked before anything is written, so a refused
        // configuration cannot leave one account seeded and the other not.
        rechazarPlaceholder(adminPassword, "ADMIN_BOOTSTRAP_PASSWORD", adminUsername);
        rechazarPlaceholder(servicioPassword, "CLI_SERVICE_ACCOUNT_PASSWORD", servicioUsername);

        String hashAdmin = hasher.hash(adminPassword);
        String hashServicio = hasher.hash(servicioPassword);

        int adoptadas = usuarios.enTransaccion(tx -> {
            // email is null for both: the service account's CHECK requires it,
            // and the bootstrap admin has no address anybody has confirmed.
            UUID adminId = tx.sembrarCuenta(adminUsername, null, hashAdmin, false, ROL_ADMIN);
            tx.sembrarCuenta(servicioUsername, null, hashServicio, true, ROL_ADMIN);
            return tx.adoptarFilasSinDueno(adminId);
        });

        if (adoptadas > 0) {
            LOG.info("[AUTH] {} filas personales preexistentes adoptadas por '{}'", adoptadas, adminUsername);
        }
    }

    private void rechazarPlaceholder(String password, String variable, String cuenta) {
        if (PLACEHOLDER.equals(password)) {
            String mensaje = "La cuenta '" + cuenta + "' NO se creó: " + variable + " sigue teniendo el "
                    + "valor de ejemplo de .env.example, que es público y lo conoce cualquiera. "
                    + "Poné una password real en tu .env y volvé a arrancar.";
            LOG.error("[AUTH] {}", mensaje);
            throw new IllegalStateException(mensaje);
        }
    }
}
