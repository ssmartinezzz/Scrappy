package ar.scraper.db;

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
 * fix-zero-yield-tech-sites, C3 (design D4 — "Two mirrors of this vocabulary
 * must move with it, or new sites silently route to Tiendanube").
 *
 * <p>{@code sitio.plataforma}'s CHECK domain and
 * {@link SitiosRepository}'s {@code PLATAFORMAS_VALIDAS} are two independent
 * copies of the same closed vocabulary — the CHECK guards a direct DB write,
 * {@code PLATAFORMAS_VALIDAS} guards a dashboard-added ({@code POST
 * /api/sitios}) write before {@code sitio} even sees it. There is
 * deliberately no third copy that would let a test just re-derive the
 * "truth" from itself ({@code CODE-6}) — this test instead parses the CHECK
 * literally out of the newest migration that redefines
 * {@code sitio_plataforma_check} (classpath-only, no DB) and asserts set
 * equality against the Java copy. Drift becomes a red build instead of a
 * site silently downgraded to {@code tiendanube}.</p>
 */
@Epic("Persistence")
@Feature("Site registry")
@Story("SitiosRepository.PLATAFORMAS_VALIDAS mirrors the sitio.plataforma CHECK domain")
@DisplayName("PLATAFORMAS_VALIDAS stays in sync with the CHECK domain (no DB)")
class PlatformVocabularySyncTest {

    /** Newest migration that redefines `sitio_plataforma_check` — update this pointer, never leave two truths. */
    private static final String NEWEST_PLATAFORMA_MIGRATION =
            "/db/migration/V27__rubro_oficina_and_inpro_platform.sql";

    private static final Pattern CHECK_LIST = Pattern.compile(
            "CHECK\\s*\\(\\s*plataforma\\s+IN\\s*\\(([^)]*)\\)\\s*\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("SitiosRepository.PLATAFORMAS_VALIDAS == el dominio CHECK de la migracion mas nueva")
    void plataformasValidasMirrorsTheCheckDomain() {
        Set<String> checkDomain = parseCheckDomain(NEWEST_PLATAFORMA_MIGRATION);

        assertThat(checkDomain).as("al menos un valor parseado del CHECK").isNotEmpty();
        assertThat(SitiosRepository.PLATAFORMAS_VALIDAS)
                .as("SitiosRepository.PLATAFORMAS_VALIDAS debe ser exactamente el dominio CHECK de %s",
                        NEWEST_PLATAFORMA_MIGRATION)
                .containsExactlyInAnyOrderElementsOf(checkDomain);
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static Set<String> parseCheckDomain(String migrationPath) {
        String sql = readClasspathResource(migrationPath);
        Matcher m = CHECK_LIST.matcher(sql);
        assertThat(m.find()).as("CHECK (plataforma IN (...)) present in " + migrationPath).isTrue();

        Set<String> values = new LinkedHashSet<>();
        for (String raw : m.group(1).split(",")) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
                values.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return values;
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = PlatformVocabularySyncTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
