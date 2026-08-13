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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 6 (V19, design DD8).
 *
 * <p>Classpath-only, no DB — same shape as {@code SitioSeedSyncTest}: parses
 * the site names out of V18's seed and asserts, exactly, that the
 * intersection of {@link BrandExtractor#MARCAS} and {@code sitio.nombre} is
 * {@code {Bulks, Fuark, Harvey}} — the three literals V19's backfill must
 * exclude (a Bulks product legitimately branded "Bulks" is real brand data,
 * not the site-name-fallback bug). If a future brand or site addition grows
 * this intersection, this test goes red instead of the exception list
 * silently going stale.</p>
 *
 * <p><b>Resolves the tasks-artifact-flagged ordering question</b>: this test
 * never executes Flyway and never touches a live database — it reads
 * {@code V18__sitio_lookup_table.sql} as classpath text, exactly like
 * {@code SitioSeedSyncTest} does. Whether V18 or V19 "runs first" against a
 * real schema is irrelevant to it, confirming design's "ordering is not
 * load-bearing" claim.</p>
 *
 * <p>close-1nf-and-3nf-foundation extension, Phase 2 (design E4, task 2.6):
 * also guards that all three exception-list brands are seeded into
 * {@code marca} — parsed from {@code V21__marca_lookup_table.sql}, same
 * classpath-only mechanism, no DB.</p>
 */
@Epic("Normalization")
@Feature("Brand")
@Story("V19 exception list is exactly MARCAS ∩ sitio.nombre")
@DisplayName("V19 — marca/sitio exception list sync (no DB)")
class MarcasSiteIntersectionTest {

    private static final String V18 = "/db/migration/V18__sitio_lookup_table.sql";
    private static final String V21 = "/db/migration/V21__marca_lookup_table.sql";

    /** The three literals V19's backfill (`marca NOT IN (...)`) must exclude. */
    private static final Set<String> EXCEPTION_LIST = Set.of("Bulks", "Fuark", "Harvey");

    @Test
    @DisplayName("MARCAS ∩ sitio.nombre es EXACTAMENTE {Bulks, Fuark, Harvey}")
    void interseccionEsExactamenteLosTresLiterales() {
        Set<String> nombresDeSitio = nombresDeSitioDelSeed();
        Set<String> nombresDeSitioLower = new LinkedHashSet<>();
        for (String n : nombresDeSitio) nombresDeSitioLower.add(n.toLowerCase());

        Set<String> interseccion = new LinkedHashSet<>();
        for (String marca : BrandExtractor.MARCAS) {
            if (nombresDeSitioLower.contains(marca.toLowerCase())) {
                interseccion.add(marca);
            }
        }

        assertThat(interseccion).containsExactlyInAnyOrderElementsOf(EXCEPTION_LIST);
    }

    @Test
    @DisplayName("los tres literales de la excepción están sembrados en marca (V21)")
    void theThreeExceptionBrandsAreSeededIntoMarca() {
        Set<String> marcaSeed = marcaSeedRows();
        for (String marca : EXCEPTION_LIST) {
            assertThat(marcaSeed).as("marca '%s' tiene que estar sembrada en V21", marca).contains(marca);
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static final Pattern PRIMER_CAMPO = Pattern.compile("\\(\\s*'([^']*)'");

    /** Parses just the `nombre` (first quoted field) of each literal seed row in V18. */
    private static Set<String> nombresDeSitioDelSeed() {
        String sql = readClasspathResource(V18);
        String marker = "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES";
        int start = sql.indexOf(marker);
        assertThat(start).as(marker + " present in " + V18).isNotEqualTo(-1);
        int end = sql.indexOf(';', start);
        assertThat(end).as("terminating ';' present after the VALUES block in " + V18).isNotEqualTo(-1);
        String block = sql.substring(start + marker.length(), end);

        Set<String> nombres = new LinkedHashSet<>();
        Matcher m = PRIMER_CAMPO.matcher(block);
        while (m.find()) nombres.add(m.group(1));
        assertThat(nombres).as("at least one site name parsed from " + V18).isNotEmpty();
        return nombres;
    }

    private static final Pattern MARCA_ROW = Pattern.compile("\\(\\s*'((?:[^']|'')*)'\\s*\\)");

    /** Parses the literal `nombre` values out of V21's `INSERT INTO marca (nombre) VALUES` block. */
    private static Set<String> marcaSeedRows() {
        String sql = readClasspathResource(V21);
        String marker = "INSERT INTO marca (nombre) VALUES";
        int start = sql.indexOf(marker);
        assertThat(start).as(marker + " present in " + V21).isNotEqualTo(-1);
        int end = sql.indexOf(';', start);
        assertThat(end).as("terminating ';' present after the VALUES block in " + V21).isNotEqualTo(-1);
        String block = sql.substring(start + marker.length(), end);

        Set<String> rows = new LinkedHashSet<>();
        Matcher m = MARCA_ROW.matcher(block);
        while (m.find()) rows.add(m.group(1).replace("''", "'"));
        assertThat(rows).as("at least one row parsed from " + V21).isNotEmpty();
        return rows;
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = MarcasSiteIntersectionTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
