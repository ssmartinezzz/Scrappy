package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.BrandExtractor;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation extension, Phase 2 (design E4).
 *
 * <p>Classpath-only, no DB — same "read the artifact" shape as
 * {@code SitioSeedSyncTest}/{@code MarcasSiteIntersectionTest}: parses
 * {@code V21__marca_lookup_table.sql}'s literal {@code INSERT ... VALUES}
 * rows and asserts they are a SUBSET of {@link BrandExtractor#MARCAS} — one
 * direction only, deliberately. {@code V21} is byte-frozen the moment it
 * ships; {@code MARCAS} is allowed to grow afterward (a new curated brand is
 * a one-line edit, re-seeded at every boot by {@code MarcaSeeder}, never a
 * new migration). Asserting the REVERSE direction (every current
 * {@code MARCAS} entry is in {@code V21}) would go red the day someone adds
 * brand #59 — exactly the coupling this design explicitly avoids.</p>
 */
@Epic("Persistence")
@Feature("Brand")
@Story("V21 seed is a subset of BrandExtractor.MARCAS")
@DisplayName("V21 — marca seed sync (no DB)")
class MarcaSeedSyncTest {

    private static final String V21 = "/db/migration/V21__marca_lookup_table.sql";

    @Test
    @DisplayName("toda fila sembrada por V21 existe en BrandExtractor.MARCAS")
    void everySeedRowIsAMarcasEntry() {
        List<String> seedRows = seedRows();
        assertThat(seedRows).as("filas parseadas de " + V21).isNotEmpty();

        for (String nombre : seedRows) {
            assertThat(BrandExtractor.MARCAS)
                    .as("fila sembrada '%s' tiene que existir en BrandExtractor.MARCAS", nombre)
                    .contains(nombre);
        }
    }

    @Test
    @DisplayName("V21 sembró la lista completa de MARCAS al momento de migrar (58 filas medidas, CODE-3)")
    void v21SeededTheFullMarcasListAtMigrationTime() {
        // Not a claim about the future (see class javadoc) — a measured fact
        // about the moment V21 was authored: every MARCAS entry that existed
        // then is present, so the FK it adds is VALID without NOT VALID.
        List<String> seedRows = seedRows();
        assertThat(seedRows).hasSize(58);
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static final Pattern ROW = Pattern.compile("\\(\\s*'((?:[^']|'')*)'\\s*\\)");

    private static List<String> seedRows() {
        String sql = readClasspathResource(V21);
        String marker = "INSERT INTO marca (nombre) VALUES";
        int start = sql.indexOf(marker);
        assertThat(start).as(marker + " present in " + V21).isNotEqualTo(-1);
        int end = sql.indexOf(';', start);
        assertThat(end).as("terminating ';' present after the VALUES block in " + V21).isNotEqualTo(-1);
        String block = sql.substring(start + marker.length(), end);

        List<String> rows = new ArrayList<>();
        Matcher m = ROW.matcher(block);
        while (m.find()) {
            rows.add(m.group(1).replace("''", "'"));
        }
        return rows;
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = MarcaSeedSyncTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
