package ar.scraper.db.migration;

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
 * manual-classification-lock, Phase 2 (task 2.1).
 *
 * <p>{@code sp_upsert_run} in {@code V3__manual_classification_lock.sql} MUST
 * carry V1's ENTIRE function body verbatim — Postgres has no partial function
 * redefinition, and {@code V1__baseline.sql} itself must never be edited
 * (Flyway validates checksums). This is a mechanical, no-DB guard against
 * copy-paste drift: it un-substitutes V3's 5 lock-guard {@code CASE}
 * expressions back to plain {@code EXCLUDED.<col>} and asserts the resulting
 * {@code DO UPDATE SET} column list is identical to V1's, then asserts every
 * other part of the function body (DECLARE block, loop, INSERT column/VALUES
 * lists, precio_historico branches, RETURN) is unchanged.</p>
 */
class SpUpsertRunDriftTest {

    private static final String FUNCTION_START = "CREATE OR REPLACE FUNCTION sp_upsert_run";
    private static final String FUNCTION_END = "$$ LANGUAGE plpgsql;";
    private static final String SET_MARKER = "ON CONFLICT (url) DO UPDATE SET";

    /** The 5 columns Decision D3 locks: categoria, sub_categoria, marca, genero, rubro. */
    private static final Pattern LOCK_GUARD = Pattern.compile(
            "CASE WHEN productos\\.bloqueado_por IS NULL THEN EXCLUDED\\.(\\w+) ELSE productos\\.(\\w+) END",
            Pattern.CASE_INSENSITIVE);

    private record ColumnAssignment(String column, String expression) {
    }

    @Test
    void v3SetClauseUnGuardedEqualsV1SetClauseExactly() {
        String v1Body = readFunctionBody("/db/migration/V1__baseline.sql");
        String v3Body = readFunctionBody("/db/migration/V3__manual_classification_lock.sql");

        List<ColumnAssignment> v1Pairs = parseSetClause(v1Body);
        List<ColumnAssignment> v3Pairs = unguard(parseSetClause(v3Body));

        assertThat(v3Pairs).as("V3's DO UPDATE SET column list, un-guarded, must equal V1's exactly (order + content)")
                .containsExactlyElementsOf(v1Pairs);
    }

    @Test
    void v3FunctionBodyOutsideTheSetClauseIsByteIdenticalToV1() {
        String v1Body = readFunctionBody("/db/migration/V1__baseline.sql");
        String v3Body = readFunctionBody("/db/migration/V3__manual_classification_lock.sql");

        String v1Remainder = normalizeWhitespace(removeSetClause(v1Body));
        String v3Remainder = normalizeWhitespace(removeSetClause(v3Body));

        assertThat(v3Remainder).isEqualTo(v1Remainder);
    }

    @Test
    void v3GuardsExactlyTheFiveDecisionD3Columns() {
        String v3Body = readFunctionBody("/db/migration/V3__manual_classification_lock.sql");
        List<ColumnAssignment> v3Pairs = parseSetClause(v3Body);

        List<String> guardedColumns = new ArrayList<>();
        for (ColumnAssignment pair : v3Pairs) {
            if (LOCK_GUARD.matcher(pair.expression()).matches()) {
                guardedColumns.add(pair.column());
            }
        }

        assertThat(guardedColumns)
                .containsExactlyInAnyOrder("categoria", "sub_categoria", "marca", "genero", "rubro");
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static String readFunctionBody(String classpathResource) {
        String full = readClasspathResource(classpathResource);
        int start = full.indexOf(FUNCTION_START);
        assertThat(start).as("sp_upsert_run definition present in " + classpathResource).isNotEqualTo(-1);
        int end = full.indexOf(FUNCTION_END, start);
        assertThat(end).as("sp_upsert_run terminator present in " + classpathResource).isNotEqualTo(-1);
        return full.substring(start, end + FUNCTION_END.length());
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = SpUpsertRunDriftTest.class.getResourceAsStream(path)) {
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

    private static String removeSetClause(String functionBody) {
        int setStart = functionBody.indexOf(SET_MARKER);
        int contentStart = setStart + SET_MARKER.length();
        int terminator = findTopLevelSemicolon(functionBody, contentStart);
        return functionBody.substring(0, setStart) + functionBody.substring(terminator + 1);
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

    private static List<ColumnAssignment> unguard(List<ColumnAssignment> pairs) {
        List<ColumnAssignment> result = new ArrayList<>();
        for (ColumnAssignment pair : pairs) {
            Matcher m = LOCK_GUARD.matcher(pair.expression());
            if (m.matches()) {
                assertThat(m.group(1)).as("CASE THEN/ELSE column must match the guarded column itself")
                        .isEqualTo(m.group(2));
                result.add(new ColumnAssignment(pair.column(), "EXCLUDED." + pair.column()));
            } else {
                result.add(pair);
            }
        }
        return result;
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
