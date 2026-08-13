package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.SiteClassification;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 5 (V18, design DD3) — REWRITTEN by the
 * 3NF extension's Phase 1 (design E1, {@code CODE-2} declared), and
 * GENERALIZED by fix-zero-yield-tech-sites, C3 (design D6, {@code CODE-2}
 * declared again): parses V18 UNION V24, not V18 alone, because Rockethard
 * and Venex are seeded by V24, not V18. Adding
 * {@code sitio.rockethard.url}/{@code sitio.venex.url} to
 * {@code config.properties} without a matching seed row is the change's own
 * declared C3 red state (site configured, no seed row found yet) — this is
 * not a bug being patched around, it is the test doing its job before V24
 * existed.
 *
 * <p>V18 seeded {@code sitio} while it was still read by nothing, so this
 * test's job was proving the seed was a faithful mirror of the four
 * name-set copies of the same knowledge ({@code SiteClassification}'s
 * {@code TECH_SITIOS}/{@code SUPPL_SITIOS}/{@code SITIOS_PREMIUM},
 * {@code ScraperFactory}'s 8 name-sets + {@code PLATAFORMA_NOMBRES}) that
 * could otherwise drift from it unnoticed.</p>
 *
 * <p>Phase 1 DELETES every one of those copies — {@code sitio}, read through
 * {@link ar.scraper.aggregator.normalize.SiteRegistry}, is now the single
 * source ({@code CODE-6}). There is nothing left to cross-check the seed's
 * {@code es_premium}/{@code rubro_forzado}/{@code plataforma} values
 * bidirectionally AGAINST — that coverage moves to
 * {@code RubroResolverEqualityParityTest} (rubro semantics, old-substring
 * oracle vs. new equality) and {@code ScraperFactoryPlatformTest} (platform
 * routing behavior). What remains here, and is still exactly the failure
 * mode a drifted seed produces, is the one check that has nothing to do with
 * the deleted sets: a site configured in {@code config.properties} but
 * missing from the {@code sitio} seed is the exact shape of the
 * {@code forever}-scrapes-0-products bug this table exists to prevent —
 * plus, new in C3, that a site whose config carries an explicit
 * {@code sitio.X.rubro=Y} is seeded with the matching
 * {@code rubro_forzado=Y} (spec "rubro_forzado Seeded for the New Tech
 * Sites").</p>
 */
@Epic("Persistence")
@Feature("Site registry")
@Story("V18 ∪ V24 seed covers every config.properties site")
@DisplayName("sitio seed sync (V18 ∪ V24, no DB)")
class SitioSeedSyncTest {

    private static final String V18 = "/db/migration/V18__sitio_lookup_table.sql";
    private static final String V24 = "/db/migration/V24__platform_vocabulary_qloud_oscommerce.sql";

    private record SeedRow(String nombre, String sitioKey, String plataforma,
                            boolean esPremium, String rubroForzado, String origen) {
    }

    @Test
    @DisplayName("todo sitio activo de config.properties tiene una fila con origen='config'")
    void todoSitioDeConfigTieneFilaConOrigenConfig() {
        List<SeedRow> rows = seedRows();
        Set<String> sitioKeysConfig = rows.stream()
                .filter(r -> "config".equals(r.origen()))
                .map(SeedRow::sitioKey)
                .collect(java.util.stream.Collectors.toSet());

        for (String nombreConfig : sitiosActivosDeConfigProperties()) {
            assertThat(sitioKeysConfig)
                    .as("sitio.%s.url activo en config.properties tiene que estar sembrado con origen='config'", nombreConfig)
                    .contains(SiteClassification.sitioKey(nombreConfig));
        }
    }

    @Test
    @DisplayName("todo sitio con sitio.X.rubro=Y en config.properties tiene rubro_forzado=Y sembrado")
    void todoSitioConRubroDeConfigTieneRubroForzadoSembrado() {
        List<SeedRow> rows = seedRows();
        Map<String, String> rubroForzadoPorKey = rows.stream()
                .collect(java.util.stream.Collectors.toMap(SeedRow::sitioKey, r -> r.rubroForzado() == null ? "" : r.rubroForzado(),
                        (a, b) -> a));

        for (var entry : sitiosConRubroDeConfigProperties().entrySet()) {
            String sitioKey = SiteClassification.sitioKey(entry.getKey());
            assertThat(rubroForzadoPorKey)
                    .as("sitio.%s.rubro=%s en config.properties", entry.getKey(), entry.getValue())
                    .containsEntry(sitioKey, entry.getValue());
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static List<String> sitiosActivosDeConfigProperties() {
        List<String> nombres = new ArrayList<>();
        Pattern urlLine = Pattern.compile("^sitio\\.([a-z0-9]+)\\.url=");
        Pattern activoFalse = Pattern.compile("^sitio\\.([a-z0-9]+)\\.activo=false");
        Set<String> inactivos = new LinkedHashSet<>();
        String texto = readClasspathResource("/config.properties");
        for (String line : texto.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue;
            Matcher mInactivo = activoFalse.matcher(trimmed);
            if (mInactivo.find()) inactivos.add(mInactivo.group(1));
        }
        for (String line : texto.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue;
            Matcher m = urlLine.matcher(trimmed);
            if (m.find()) {
                String nombre = m.group(1);
                if (!inactivos.contains(nombre)) nombres.add(nombre);
            }
        }
        return nombres;
    }

    /** Every `sitio.X.rubro=Y` declared in config.properties, keyed by the raw config name. */
    private static Map<String, String> sitiosConRubroDeConfigProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        Pattern rubroLine = Pattern.compile("^sitio\\.([a-z0-9]+)\\.rubro=(\\S+)");
        String texto = readClasspathResource("/config.properties");
        for (String line : texto.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue;
            Matcher m = rubroLine.matcher(trimmed);
            if (m.find()) result.put(m.group(1), m.group(2));
        }
        return result;
    }

    private static final Pattern ROW = Pattern.compile(
            "\\(\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*(true|false)\\s*,\\s*('([^']*)'|NULL)\\s*,\\s*'([^']*)'\\s*\\)");

    /**
     * Parses the literal `INSERT INTO sitio (...) VALUES (...), (...), ...;`
     * rows out of every migration that seeds {@code sitio} — V18 (the
     * original 23-row seed) UNION V24 (Rockethard/Venex, fix-zero-yield-tech-sites
     * C3). Each contributing migration adds exactly one such block; a
     * migration with no matching block contributes nothing rather than
     * failing, so this stays generic as more migrations seed the table.
     */
    private static List<SeedRow> seedRows() {
        List<SeedRow> rows = new ArrayList<>();
        for (String migration : List.of(V18, V24)) {
            rows.addAll(seedRowsFrom(migration));
        }
        assertThat(rows).as("at least one seed row parsed across V18 ∪ V24").isNotEmpty();
        return rows;
    }

    private static List<SeedRow> seedRowsFrom(String migrationPath) {
        String sql = readClasspathResource(migrationPath);
        String marker = "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES";
        int start = sql.indexOf(marker);
        assertThat(start).as(marker + " present in " + migrationPath).isNotEqualTo(-1);
        int end = sql.indexOf(';', start);
        assertThat(end).as("terminating ';' present after the VALUES block in " + migrationPath).isNotEqualTo(-1);
        String block = sql.substring(start + marker.length(), end);

        List<SeedRow> rows = new ArrayList<>();
        Matcher m = ROW.matcher(block);
        while (m.find()) {
            String nombre = m.group(1);
            String sitioKey = m.group(2);
            String plataforma = m.group(3);
            boolean esPremium = Boolean.parseBoolean(m.group(4));
            String rubroForzado = m.group(6); // null if the NULL literal matched
            String origen = m.group(7);
            rows.add(new SeedRow(nombre, sitioKey, plataforma, esPremium, rubroForzado, origen));
        }
        assertThat(rows).as("at least one seed row parsed from " + migrationPath).isNotEmpty();
        return rows;
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = SitioSeedSyncTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
