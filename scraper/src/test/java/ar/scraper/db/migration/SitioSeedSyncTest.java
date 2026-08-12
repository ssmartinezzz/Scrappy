package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.scrapers.ScraperFactory;
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
 * close-1nf-and-3nf-foundation, Phase 5 (V18, design DD3).
 *
 * <p>Classpath-only, no DB — same shape as {@code StoredProcedureDriftTest}:
 * parses the {@code sitio} seed's literal {@code INSERT ... VALUES} rows out
 * of {@code V18__sitio_lookup_table.sql} and asserts, in BOTH directions,
 * that the seed is an asserted mirror of {@link SiteClassification} and
 * {@link ScraperFactory} rather than a fourth copy of the same knowledge
 * that can drift unnoticed (design's stated blocker for this table).</p>
 */
@Epic("Persistence")
@Feature("Site registry")
@Story("V18 seed mirrors SiteClassification/ScraperFactory/config.properties")
@DisplayName("V18 — sitio seed sync (no DB)")
class SitioSeedSyncTest {

    private static final String V18 = "/db/migration/V18__sitio_lookup_table.sql";

    private record SeedRow(String nombre, String sitioKey, String plataforma,
                            boolean esPremium, String rubroForzado, String origen) {
    }

    @Test
    @DisplayName("es_premium ⟺ SiteClassification.SITIOS_PREMIUM, en las dos direcciones")
    void esPremiumEsBidireccionalConSitiosPremium() {
        List<SeedRow> rows = seedRows();

        for (SeedRow row : rows) {
            assertThat(row.esPremium())
                    .as("fila '%s' (sitio_key=%s): es_premium debe reflejar SITIOS_PREMIUM", row.nombre(), row.sitioKey())
                    .isEqualTo(SiteClassification.SITIOS_PREMIUM.contains(row.sitioKey()));
        }

        Set<String> sitioKeysSemeadas = rows.stream().map(SeedRow::sitioKey).collect(java.util.stream.Collectors.toSet());
        for (String premium : SiteClassification.SITIOS_PREMIUM) {
            assertThat(sitioKeysSemeadas)
                    .as("SITIOS_PREMIUM '%s' tiene que estar sembrado", premium)
                    .contains(premium);
        }
    }

    @Test
    @DisplayName("rubro_forzado ⟺ TECH_SITIOS/SUPPL_SITIOS (nombres bare), en las dos direcciones")
    void rubroForzadoEsBidireccionalConTechYSupplSitios() {
        List<SeedRow> rows = seedRows();
        Set<String> techBare = soloNombresBare(SiteClassification.TECH_SITIOS);
        Set<String> supplBare = soloNombresBare(SiteClassification.SUPPL_SITIOS);

        for (SeedRow row : rows) {
            String esperado = techBare.contains(row.sitioKey()) ? "tecnologia"
                    : supplBare.contains(row.sitioKey()) ? "suplementos"
                    : null;
            assertThat(row.rubroForzado())
                    .as("fila '%s' (sitio_key=%s): rubro_forzado", row.nombre(), row.sitioKey())
                    .isEqualTo(esperado);
        }

        Set<String> sitioKeysSemeadas = rows.stream().map(SeedRow::sitioKey).collect(java.util.stream.Collectors.toSet());
        for (String tech : techBare) {
            assertThat(sitioKeysSemeadas).as("TECH_SITIOS '%s' tiene que estar sembrado", tech).contains(tech);
        }
        for (String suppl : supplBare) {
            assertThat(sitioKeysSemeadas).as("SUPPL_SITIOS '%s' tiene que estar sembrado", suppl).contains(suppl);
        }
    }

    @Test
    @DisplayName("plataforma ⟺ los 8 name-sets de ScraperFactory, else 'tiendanube'")
    void plataformaEsBidireccionalConScraperFactory() {
        List<SeedRow> rows = seedRows();

        for (SeedRow row : rows) {
            String esperado = "tiendanube";
            for (var entry : ScraperFactory.PLATAFORMA_NOMBRES.entrySet()) {
                if (entry.getValue().contains(row.sitioKey())) {
                    esperado = entry.getKey();
                    break;
                }
            }
            assertThat(row.plataforma())
                    .as("fila '%s' (sitio_key=%s): plataforma", row.nombre(), row.sitioKey())
                    .isEqualTo(esperado);
        }
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

    /** Nombres bare (sin punto) de un set que mezcla nombre de sitio y variantes con dominio. */
    private static Set<String> soloNombresBare(Set<String> sitios) {
        Set<String> bare = new LinkedHashSet<>();
        for (String s : sitios) {
            if (!s.contains(".")) bare.add(s);
        }
        return bare;
    }

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
