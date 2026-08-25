package ar.scraper.db.migration;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 1 — a content assertion over the migration
 * <b>file</b>, deliberately not over the database.
 *
 * <p>An applied migration is byte-frozen: Flyway validates checksums, so a
 * credential that ships inside {@code V26} can never be edited out — it can only
 * be superseded by another migration, while the original stays in the file, in
 * the history, and in every clone of a public repository. This is the one class
 * of mistake in this change that cannot be fixed after the fact, which is why it
 * gets a test that runs before the file is ever applied anywhere.</p>
 *
 * <p>The same reasoning covers a hardcoded user id: ownership backfill happens
 * in application code after the bootstrap admin is seeded, precisely so the
 * frozen SQL never has to name a row that does not exist at migration time.</p>
 */
@Epic("Security")
@Feature("Public-repo constraints")
@Story("V26 carries no credential and no literal user id")
@DisplayName("V26 migration — file content")
class V26NoCredentialsTest {

    private static final int MAX_NIVELES_HACIA_ARRIBA = 6;

    private static String sql;

    @BeforeAll
    static void leerMigracion() {
        sql = leerArchivo("V26__usuario_rol_refresh_token.sql");
    }

    @Test
    @DisplayName("no password, hash or credential literal anywhere in the file")
    void carriesNoCredential() {
        List<Pattern> prohibidos = List.of(
                Pattern.compile("\\$argon2", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\$2[aby]\\$"),                       // bcrypt
                Pattern.compile("password_hash\\s*\\)?\\s*VALUES", Pattern.CASE_INSENSITIVE),
                Pattern.compile("INSERT\\s+INTO\\s+usuario\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bsecret\\b", Pattern.CASE_INSENSITIVE));

        for (Pattern prohibido : prohibidos) {
            assertThat(prohibido.matcher(sql).find())
                    .as("V26 must not contain %s — a byte-frozen file cannot be edited to remove it",
                            prohibido.pattern())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("no literal UUID — ownership backfill names no row")
    void carriesNoLiteralUserId() {
        Pattern uuidLiteral = Pattern.compile(
                "'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'");

        assertThat(uuidLiteral.matcher(sql).find())
                .as("at migration time no usuario row exists, so any id written here would be a guess")
                .isFalse();
    }

    @Test
    @DisplayName("the ownership columns are added nullable, never NOT NULL")
    void ownerColumnsAreAddedNullable() {
        assertThat(sql)
                .as("a NOT NULL owner column cannot be added before the owner row exists")
                .doesNotContain("usuario_id UUID NOT NULL REFERENCES");
    }

    private static String leerArchivo(String nombre) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < MAX_NIVELES_HACIA_ARRIBA && dir != null; i++) {
            Path candidato = dir.resolve("scraper").resolve("src").resolve("main")
                    .resolve("resources").resolve("db").resolve("migration").resolve(nombre);
            if (Files.isRegularFile(candidato)) {
                try {
                    return Files.readString(candidato);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(nombre + " no encontrado subiendo desde "
                + System.getProperty("user.dir"));
    }
}
