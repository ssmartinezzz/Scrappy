package ar.scraper.aggregator.normalize;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation extension, Phase 1 (design E3) — the CRITICAL
 * item: {@link RubroResolver} moves from substring matching
 * ({@code sitioKey.contains(token)}) to equality lookups against
 * {@link SiteRegistry}. This test proves the new implementation agrees with
 * the OLD substring implementation (kept here, in the test file ONLY —
 * {@code CODE-6} — never in {@code main}) across every {@code sitio_key}
 * parsed out of {@code V18__sitio_lookup_table.sql} (the same "read the
 * artifact, don't paraphrase it" mechanism {@code SitioSeedSyncTest} uses) ×
 * every {@link CategoryGroups#canonicalCategories()} category (103 desde
 * richer-category-taxonomy; eran 88, y 81 antes de eso) × every
 * {@code rubroExistente} in {@code {null, "", "tecnologia", "suplementos",
 * "indumentaria"}} — 23 × 103 × 5 = 11,845 triples.
 *
 * <p>Any disagreement fails with the exact {@code (sitio, categoria,
 * rubroPrevio)} triple — the "set of affected products" made enumerable
 * instead of argued. Design's own finding: zero of the 23 real config sites
 * actually change (each of the four matching tokens equals its site key
 * exactly), which this test proves mechanically rather than asserting by
 * inspection.</p>
 */
@Epic("Normalization")
@Feature("Site classification")
@Story("RubroResolver equality agrees with the old substring oracle")
@DisplayName("RubroResolver — substring vs. equality parity (no DB)")
class RubroResolverEqualityParityTest {

    private static final String V18 = "/db/migration/V18__sitio_lookup_table.sql";

    /** The OLD implementation, verbatim (pre close-1nf-and-3nf-foundation extension) — oracle, test-file-only. */
    private static final Set<String> OLD_TECH_SITIOS = Set.of(
            "compragamer", "fullh4rd", "maximus",
            "compragamer.com", "fullh4rd.com.ar", "maximus.com.ar");
    private static final Set<String> OLD_SUPPL_SITIOS = Set.of("entreno", "entreno.com.ar");

    private static String oldSubstringResolver(String sitioKey, String cat, String rubroExistente) {
        boolean catEsTextil = CategoryGroups.esIndumentariaOCalzado(cat);
        boolean catEsSuppl  = CategoryGroups.esCategoriaSuplemento(cat);

        if (OLD_TECH_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]", "")))
                && !catEsTextil) {
            return "tecnologia";
        } else if (catEsSuppl) {
            return "suplementos";
        } else if (OLD_SUPPL_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]", "")))
                && !catEsTextil) {
            return "suplementos";
        } else if (catEsTextil) {
            return "indumentaria";
        } else if (rubroExistente != null && !rubroExistente.isBlank()) {
            return rubroExistente;
        } else {
            return "indumentaria";
        }
    }

    @Test
    @DisplayName("equality agrees with the old substring oracle over 23 sitios x 103 categorías x 5 rubros previos")
    void equalityAgreesWithOldSubstringOracleAcrossTheFullMatrix() {
        List<SeedRow> rows = seedRows();
        Map<String, SiteRegistry.Sitio> cache = new HashMap<>();
        for (SeedRow r : rows) {
            cache.put(r.sitioKey(), new SiteRegistry.Sitio(
                    r.nombre(), r.sitioKey(), r.plataforma(), r.esPremium(), r.rubroForzado(), r.origen()));
        }
        SiteRegistry registry = SiteRegistry.forTesting(cache);
        RubroResolver equality = new RubroResolver(registry);

        Set<String> sitioKeys = new LinkedHashSet<>();
        for (SeedRow r : rows) sitioKeys.add(r.sitioKey());
        assertThat(sitioKeys).as("sitio_key entries parsed from " + V18).hasSize(23);

        Set<String> cats = CategoryGroups.canonicalCategories();
        // add-inpro-office-store, CODE-2 declarado: 81 -> 88. El canon CRECIÓ
        // (siete categorías de oficina), no se movió: el oráculo de paridad de
        // este test compara el substring viejo contra la igualdad nueva sobre
        // los 23 sitios de V18, y ninguno de ellos tiene rubro_forzado='oficina',
        // así que el resultado por sitio x categoría no cambia. Lo único que
        // cambia es el tamaño del producto cartesiano que se recorre.
        //
        // richer-category-taxonomy, CODE-2 declarado: 88 -> 103. Mismo
        // argumento y mismo resultado. Las quince nuevas son de tecnología y
        // equipamiento deportivo, y RubroResolver no deriva el rubro de la
        // categoría salvo para suplementos — ninguna de las quince lo es.
        //
        // V32: 103 -> 105. Estas DOS sí son de suplementos ("Proteína Isolada",
        // "Proteína Vegetal"), así que a diferencia de las quince anteriores el
        // oráculo sí las mira. La paridad se sostiene igual porque las dos
        // entraron a CATEGORIAS_SUPLEMENTO junto con la categoría: la igualdad
        // nueva las reconoce, y el substring viejo también las reconocía por
        // contener "Proteína". El barrido completo de abajo es lo que lo prueba.
        assertThat(cats).as("CategoryGroups.canonicalCategories()").hasSize(105);

        List<String> rubrosExistentes = Arrays.asList(null, "", "tecnologia", "suplementos", "indumentaria");

        List<String> mismatches = new ArrayList<>();
        long total = 0;
        for (String sitioKey : sitioKeys) {
            for (String cat : cats) {
                for (String rubroExistente : rubrosExistentes) {
                    total++;
                    String oldResult = oldSubstringResolver(sitioKey, cat, rubroExistente);
                    String newResult = equality.resolver(sitioKey, cat, rubroExistente);
                    if (!Objects.equals(oldResult, newResult)) {
                        mismatches.add("(sitio=%s, categoria=%s, rubroPrevio=%s): old=%s new=%s"
                                .formatted(sitioKey, cat, rubroExistente, oldResult, newResult));
                    }
                }
            }
        }

        assertThat(total).isEqualTo(23L * 105 * 5);
        assertThat(mismatches)
                .as("old substring oracle vs new equality — mismatched (sitio, categoria, rubroPrevio) triples")
                .isEmpty();
    }

    // ─── helpers — same parsing mechanism as SitioSeedSyncTest ────────────────

    private record SeedRow(String nombre, String sitioKey, String plataforma,
                            boolean esPremium, String rubroForzado, String origen) {
    }

    private static final Pattern ROW = Pattern.compile(
            "\\(\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*(true|false)\\s*,\\s*('([^']*)'|NULL)\\s*,\\s*'([^']*)'\\s*\\)");

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
            rows.add(new SeedRow(m.group(1), m.group(2), m.group(3),
                    Boolean.parseBoolean(m.group(4)), m.group(6), m.group(7)));
        }
        assertThat(rows).as("at least one seed row parsed from " + V18).isNotEmpty();
        return rows;
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = RubroResolverEqualityParityTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
