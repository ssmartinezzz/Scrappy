package ar.scraper.security;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.FaultInjection;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestRepositories;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Authentication")
@Feature("Bootstrap")
@Story("Seeding and adoption are one transaction")
@DisplayName("AdminSeeder — a failed adoption rolls the seeded accounts back")
class AdminSeederRollbackTest extends PostgresTestBase {

    @Test
    @DisplayName("no account survives when adopting the ownerless rows fails")
    void noDanglingOwnerWhenAdoptionFails() throws Exception {
        UsuarioRepository repo = TestRepositories.usuarios(dataSource());
        var seeder = new AdminSeeder(repo, new PasswordHasher(),
                "admin", "una-password-de-verdad", "cli", "otra-password-de-verdad");
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO categoria_dismiss (categoria, created_at) VALUES ('remeras', now())");
        }

        try (var fault = FaultInjection.raiseOn(dataSource(), "categoria_dismiss", "UPDATE", null)) {
            assertThatThrownBy(() -> seeder.run(null))
                    .isInstanceOf(UsuarioRepository.DatabaseException.class);
        }

        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM usuario")) {
            rs.next();
            assertThat(rs.getInt(1)).as("a seeded admin with a failed adoption would leave rows unclaimed").isZero();
        }
    }
}
