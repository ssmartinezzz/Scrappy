package ar.scraper.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 2 (task 2.1) — generalized by
 * normalize-db-schema-fks-1nf, slice A.2 (task 2.8, design D1), then
 * generalized a second time (verify-report #847 WARNING 1) to also cover
 * {@code sp_soft_delete_ausentes} — renamed from {@code SpUpsertRunDriftTest}
 * because it no longer describes only one function.
 *
 * <p>Neither {@code sp_upsert_run} nor {@code sp_soft_delete_ausentes} has a
 * partial redefinition in Postgres, and {@code V1__baseline.sql}/
 * {@code V3__manual_classification_lock.sql} are never edited (Flyway
 * validates checksums), so every migration that touches either function MUST
 * carry the entire previous body forward verbatim, with only its own change
 * applied. This is a mechanical, no-DB guard against copy-paste drift: for
 * each {@link Hop} in a function's chain, it undoes that hop's own declared
 * {@link Substitution}s on the CURRENT body and asserts the result is
 * byte-identical (modulo whitespace) to the PREVIOUS body — proving nothing
 * <em>else</em> changed relative to the real previous migration.</p>
 *
 * <p>{@code sp_upsert_run}'s chain is {@code V1 -> V3 -> V5} (V3 redefines
 * it for the manual-classification lock). {@code sp_soft_delete_ausentes} is
 * NOT redefined by V3 — confirmed by reading V3__manual_classification_lock.sql,
 * which touches only {@code sp_upsert_run} — so its chain is the single hop
 * {@code V1 -> V5}.</p>
 *
 * <p>Rejected (design D1): freezing a golden text file of the expected body
 * — it creates a second copy of the same ~90 lines to maintain and drops the
 * property this test exists for.</p>
 */
class StoredProcedureDriftTest {

    private static final String V1 = "/db/migration/V1__baseline.sql";
    private static final String V3 = "/db/migration/V3__manual_classification_lock.sql";
    private static final String V5 = "/db/migration/V5__boolean_and_date_column_types.sql";

    private static final String SP_UPSERT_RUN_START = "CREATE OR REPLACE FUNCTION sp_upsert_run";
    private static final String SP_SOFT_DELETE_AUSENTES_START =
            "CREATE OR REPLACE FUNCTION sp_soft_delete_ausentes";
    private static final String FUNCTION_END = "$$ LANGUAGE plpgsql;";
    private static final String SET_MARKER = "ON CONFLICT (url) DO UPDATE SET";

    /** One declared, named transformation applied to CURRENT's body before comparing to PREVIOUS's. */
    private record Substitution(String description, Pattern pattern, String replacement) {
    }

    /** {@code functionStart} scopes body extraction to one function when a file defines several. */
    private record Hop(String functionStart, String previous, String current, List<Substitution> substitutions) {
    }

    /** Decision D3 (manual-classification-lock): the 5 columns V3 locks behind bloqueado_por. */
    private static final Substitution UNGUARD_LOCKED_COLUMNS = new Substitution(
            "V3 lock-guard CASE -> plain EXCLUDED.<col> (Decision D3, 5 locked columns)",
            Pattern.compile(
                    "CASE WHEN productos\\.bloqueado_por IS NULL THEN EXCLUDED\\.(\\w+) ELSE productos\\.\\1 END"),
            "EXCLUDED.$1");

    /** Design D2/D6 (normalize-db-schema-fks-1nf slice A.2): boolean + date/timestamptz casts introduced by V5. */
    private static final List<Substitution> SP_UPSERT_RUN_V5_CASTS = List.of(
            new Substitution(
                    "activo boolean predicate -> activo = 1 (productos.activo INTEGER pre-V5)",
                    Pattern.compile("WHERE url = r->>'url' AND activo;"),
                    "WHERE url = r->>'url' AND activo = 1;"),
            new Substitution(
                    "mlOferta boolean cast -> INTEGER cast",
                    Pattern.compile("COALESCE\\(\\(r->>'mlOferta'\\)::boolean, false\\)"),
                    "COALESCE((r->>'mlOferta')::INTEGER, 0)"),
            new Substitution(
                    "gymrat boolean cast -> INTEGER cast",
                    Pattern.compile("COALESCE\\(\\(r->>'gymrat'\\)::boolean, false\\)"),
                    "COALESCE((r->>'gymrat')::INTEGER, 0)"),
            new Substitution(
                    "marcaPremium boolean cast -> INTEGER cast",
                    Pattern.compile("COALESCE\\(\\(r->>'marcaPremium'\\)::boolean, false\\)"),
                    "COALESCE((r->>'marcaPremium')::INTEGER, 0)"),
            new Substitution(
                    "INSERT VALUES tail: activo literal + touched_at/created_at timestamptz casts -> plain text",
                    Pattern.compile(
                            "v_fit, v_estampado, v_escote, v_color, true, \\(r->>'now'\\)::timestamptz, \\(r->>'now'\\)::timestamptz"),
                    "v_fit, v_estampado, v_escote, v_color, 1, r->>'now', r->>'now'"),
            new Substitution(
                    "DO UPDATE SET activo = true -> activo = 1",
                    Pattern.compile("activo = true,"),
                    "activo = 1,"),
            new Substitution(
                    "precio_historico.fecha date cast -> plain text (2 occurrences: nuevos/actualizados branches)",
                    Pattern.compile("VALUES \\(r->>'url', v_new_precio, \\(r->>'fecha'\\)::date\\)"),
                    "VALUES (r->>'url', v_new_precio, r->>'fecha')")
    );

    /**
     * Design D2/D6, task 2.5: {@code sp_soft_delete_ausentes} gains the same
     * boolean/timestamptz casts as {@code sp_upsert_run}, in its own single
     * V1 -> V5 hop (V3 never touches this function).
     */
    private static final List<Substitution> SP_SOFT_DELETE_AUSENTES_V5_CASTS = List.of(
            new Substitution(
                    "SET activo boolean literal -> INTEGER literal",
                    Pattern.compile("SET activo = false,"),
                    "SET activo = 0,"),
            new Substitution(
                    "touched_at timestamptz cast on p_now -> plain text param",
                    Pattern.compile("touched_at = p_now::timestamptz"),
                    "touched_at = p_now"),
            new Substitution(
                    "WHERE activo boolean predicate -> activo = 1",
                    Pattern.compile("WHERE activo AND NOT"),
                    "WHERE activo = 1 AND NOT")
    );

    private static final List<Hop> CHAIN = List.of(
            new Hop(SP_UPSERT_RUN_START, V1, V3, List.of(UNGUARD_LOCKED_COLUMNS)),
            new Hop(SP_UPSERT_RUN_START, V3, V5, SP_UPSERT_RUN_V5_CASTS),
            new Hop(SP_SOFT_DELETE_AUSENTES_START, V1, V5, SP_SOFT_DELETE_AUSENTES_V5_CASTS)
    );

    @Test
    void everyHopInTheChainIsIdenticalModuloItsDeclaredSubstitutions() {
        for (Hop hop : CHAIN) {
            String previousBody = normalizeWhitespace(readFunctionBody(hop.previous(), hop.functionStart()));
            String currentBody = normalizeWhitespace(readFunctionBody(hop.current(), hop.functionStart()));

            String undone = currentBody;
            for (Substitution s : hop.substitutions()) {
                String before = undone;
                // Not quoted: UNGUARD_LOCKED_COLUMNS relies on the $1 backreference.
                undone = s.pattern().matcher(undone).replaceAll(s.replacement());
                assertThat(undone).as("substitution '%s' (%s -> %s) must actually match something",
                                s.description(), hop.current(), hop.previous())
                        .isNotEqualTo(before);
            }

            assertThat(undone)
                    .as("%s's body, with its declared substitutions undone, must equal %s's body exactly (function %s)",
                            hop.current(), hop.previous(), hop.functionStart())
                    .isEqualTo(previousBody);
        }
    }

    @Test
    void v3GuardsExactlyTheFiveDecisionD3Columns() {
        String v3Body = readFunctionBody(V3, SP_UPSERT_RUN_START);
        List<ColumnAssignment> v3Pairs = parseSetClause(v3Body);

        Pattern lockGuard = Pattern.compile(
                "CASE WHEN productos\\.bloqueado_por IS NULL THEN EXCLUDED\\.(\\w+) ELSE productos\\.(\\w+) END",
                Pattern.CASE_INSENSITIVE);
        List<String> guardedColumns = new ArrayList<>();
        for (ColumnAssignment pair : v3Pairs) {
            if (lockGuard.matcher(pair.expression()).matches()) {
                guardedColumns.add(pair.column());
            }
        }

        assertThat(guardedColumns)
                .containsExactlyInAnyOrder("categoria", "sub_categoria", "marca", "genero", "rubro");
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private record ColumnAssignment(String column, String expression) {
    }

    private static String readFunctionBody(String classpathResource, String functionStart) {
        String full = readClasspathResource(classpathResource);
        int start = full.indexOf(functionStart);
        assertThat(start).as(functionStart + " definition present in " + classpathResource).isNotEqualTo(-1);
        int end = full.indexOf(FUNCTION_END, start);
        assertThat(end).as(functionStart + " terminator present in " + classpathResource).isNotEqualTo(-1);
        return full.substring(start, end + FUNCTION_END.length());
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = StoredProcedureDriftTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses the {@code column = expression, ...} pairs inside DO UPDATE SET, honoring paren depth. */
    private static List<ColumnAssignment> parseSetClause(String functionBody) {
        int setStart = functionBody.indexOf(SET_MARKER);
        assertThat(setStart).as("DO UPDATE SET marker present").isNotEqualTo(-1);
        int contentStart = setStart + SET_MARKER.length();
        int terminator = findTopLevelSemicolon(functionBody, contentStart);
        String clause = functionBody.substring(contentStart, terminator);

        List<String> rawPairs = splitTopLevel(clause, ',');
        List<ColumnAssignment> pairs = new ArrayList<>();
        for (String rawPair : rawPairs) {
            String trimmed = rawPair.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            String col = trimmed.substring(0, eq).trim();
            String expr = normalizeWhitespace(trimmed.substring(eq + 1).trim());
            pairs.add(new ColumnAssignment(col, expr));
        }
        return pairs;
    }

    private static int findTopLevelSemicolon(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ';' && depth == 0) return i;
        }
        throw new IllegalStateException("No top-level ';' found terminating DO UPDATE SET clause");
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == separator && depth == 0) {
                parts.add(text.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(text.substring(last));
        return parts;
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
