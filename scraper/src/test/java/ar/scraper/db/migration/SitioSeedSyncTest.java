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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 5 (V18, design DD3) — REWRITTEN by the
 * 3NF extension's Phase 1 (design E1, {@code CODE-2} declared).
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
 * {@code forever}-scrapes-0-products bug this table exists to prevent.</p>
 */
@Epic("Persistence")
@Feature("Site registry")
@Story("V18 seed covers every config.properties site")
@DisplayName("V18 — sitio seed sync (no DB)")
class SitioSeedSyncTest {

    private static final String V18 = "/db/migration/V18__sitio_lookup_table.sql";

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

    private static final Pattern ROW = Pattern.compile(
            "\\(\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*(true|false)\\s*,\\s*('([^']*)'|NULL)\\s*,\\s*'([^']*)'\\s*\\)");

    /** Parses the literal `INSERT INTO sitio (...) VALUES (...), (...), ...;` rows. */
    private static List<SeedRow> seedRows() {
        String sql = readClasspathResource(V18);
        String marker = "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES";
        int start = sql.indexOf(marker);
        assertThat(start).as(marker + " present in " + V18).isNotEqualTo(-1);
        int end = sql.indexOf(';', start);
        assertThat(end).as("terminating ';' present after the VALUES block in " + V18).isNotEqualTo(-1);
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
        assertThat(rows).as("at least one seed row parsed from " + V18).isNotEmpty();
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
