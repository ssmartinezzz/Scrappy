package ar.scraper.security;

import ar.scraper.db.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Why an {@link ApplicationRunner} and not a migration. */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSeeder.class);

    /**
     * Refusing it closes the likeliest path to a world-known admin password: copying the example
     * file and never editing it.
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
        // Both passwords are checked before anything is written, so a refused configuration cannot
        // leave one account seeded and the other not.
        rechazarPlaceholder(adminPassword, "ADMIN_BOOTSTRAP_PASSWORD", adminUsername);
        rechazarPlaceholder(servicioPassword, "CLI_SERVICE_ACCOUNT_PASSWORD", servicioUsername);

        String hashAdmin = hasher.hash(adminPassword);
        String hashServicio = hasher.hash(servicioPassword);

        int adoptadas = usuarios.sembrarAdministracion(
                adminUsername, hashAdmin, servicioUsername, hashServicio, ROL_ADMIN);

        if (adoptadas > 0) {
            LOG.info("[AUTH] {} filas personales preexistentes adoptadas por '{}'", adoptadas, adminUsername);
        }
    }

    private void rechazarPlaceholder(String password, String variable, String cuenta) {
        if (Placeholders.isExample(password)) {
            String mensaje = "La cuenta '" + cuenta + "' NO se creó: " + variable + " sigue teniendo un "
                    + "valor de ejemplo de .env.example, que es público y lo conoce cualquiera. "
                    + "Poné una password real en tu .env y volvé a arrancar.";
            LOG.error("[AUTH] {}", mensaje);
            throw new IllegalStateException(mensaje);
        }
    }
}
