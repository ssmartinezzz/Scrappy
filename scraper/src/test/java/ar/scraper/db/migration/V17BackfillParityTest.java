package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 4 (V17, design DD2/DD7).
 *
 * <p>{@code sp_parse_precio_ar} — the SQL function V17 uses ONCE to backfill
 * the existing {@code precio_orig} TEXT column into {@code double precision}
 * — must parse byte-for-byte identically to
 * {@link ar.scraper.aggregator.text.PrecioParser}. Both are pinned against
 * the SAME fixture, {@code price-parser-cases.tsv}: neither can go green
 * while the two implementations' semantics differ (design DD2's anti-drift
 * mechanism, same shape as {@code StoredProcedureDriftTest}).</p>
 *
 * <p>The function is extracted out of {@code V17__*.sql} and created inside a
 * transaction that always rolls back — the real migration drops it right
 * after the backfill, so this test never leaves it behind either.</p>
 */
@Epic("Persistence")
@Feature("Price normalization")
@Story("sp_parse_precio_ar mirrors PrecioParser exactly")
@DisplayName("V17 — sp_parse_precio_ar backfill parser matches PrecioParser")
class V17BackfillParityTest extends PostgresTestBase {

    private static final String V17 = "/db/migration/V17__precio_orig_numeric.sql";
    private static final String FUNCTION_START = "CREATE FUNCTION sp_parse_precio_ar";
    private static final String FUNCTION_END = "$$ LANGUAGE plpgsql IMMUTABLE;";

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("fixtureCases")
    @DisplayName("sp_parse_precio_ar sobre cada caso del fixture compartido con PrecioParserTest")
    void sqlParityConPrecioParser(String raw, String esperado) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute(functionBody());

                try (PreparedStatement ps = c.prepareStatement("SELECT sp_parse_precio_ar(?)")) {
                    ps.setString(1, raw);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        double valor = rs.getDouble(1);
                        boolean esNull = rs.wasNull();
                        if ("NULL".equals(esperado)) {
                            assertThat(esNull).as("raw=\"%s\" debe parsear a NULL", raw).isTrue();
                        } else {
                            assertThat(esNull).as("raw=\"%s\" NO debe parsear a NULL", raw).isFalse();
                            assertThat(valor).isEqualTo(Double.parseDouble(esperado));
                        }
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> fixtureCases() throws IOException {
        List<org.junit.jupiter.params.provider.Arguments> args = new ArrayList<>();
        try (InputStream in = V17BackfillParityTest.class.getClassLoader()
                .getResourceAsStream("price-parser-cases.tsv")) {
            if (in == null) throw new IOException("price-parser-cases.tsv no encontrado en el classpath");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab < 0) continue;
                    String raw = line.substring(0, tab);
                    String expected = line.substring(tab + 1).trim();
                    args.add(org.junit.jupiter.params.provider.Arguments.of(raw, expected));
                }
            }
        }
        return args.stream();
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static String functionBody() {
        String full = readClasspathResource(V17);
        int start = full.indexOf(FUNCTION_START);
        assertThat(start).as(FUNCTION_START + " present in " + V17).isNotEqualTo(-1);
        int end = full.indexOf(FUNCTION_END, start);
        assertThat(end).as(FUNCTION_END + " terminator present in " + V17).isNotEqualTo(-1);
        return full.substring(start, end + FUNCTION_END.length());
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = V17BackfillParityTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
