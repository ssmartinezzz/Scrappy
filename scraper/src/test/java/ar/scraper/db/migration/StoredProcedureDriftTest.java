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
 * <p>{@code sp_upsert_run}'s chain is {@code V1 -> V3 -> V5 -> V7} (V3 redefines
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
    private static final String V7 = "/db/migration/V7__product_multivalue_child_tables.sql";
    private static final String V17 = "/db/migration/V17__precio_orig_numeric.sql";
    private static final String V21 = "/db/migration/V21__marca_lookup_table.sql";
    private static final String V22 = "/db/migration/V22__drop_marca_premium.sql";
    private static final String R_UPSERT = "/db/migration/R__sp_upsert_run.sql";
    private static final String R_SOFT_DELETE = "/db/migration/R__sp_soft_delete_ausentes.sql";

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

    /**
     * Design D3/D4 (slice B): V7 drops {@code talles}/{@code ml_badge} from the
     * three places the productos upsert names them, and adds one DELETE +
     * INSERT…WITH ORDINALITY pair per child table inside the loop. Undoing
     * exactly these four edits must land back on V5's body, byte for byte.
     */
    private static final List<Substitution> SP_UPSERT_RUN_V7_CHILD_TABLES = List.of(
            new Substitution(
                    "INSERT column list: talles/ml_badge dropped",
                    Pattern.compile("categoria, genero, ml_score, ml_oferta"),
                    "categoria, genero, talles, ml_badge, ml_score, ml_oferta"),
            new Substitution(
                    "INSERT VALUES: talles/mlBadge expressions dropped",
                    Pattern.compile("r->>'genero', COALESCE\\(\\(r->>'mlScore'\\)"),
                    "r->>'genero', COALESCE(r->>'talles', '[]'), COALESCE(r->>'mlBadge', ''), "
                            + "COALESCE((r->>'mlScore')"),
            new Substitution(
                    "DO UPDATE SET: talles/ml_badge assignments dropped",
                    Pattern.compile("productos\\.genero END, ml_score = EXCLUDED\\.ml_score,"),
                    "productos.genero END, talles = EXCLUDED.talles, ml_badge = EXCLUDED.ml_badge, "
                            + "ml_score = EXCLUDED.ml_score,"),
            new Substitution(
                    "child-table DELETE + INSERT…WITH ORDINALITY pairs added inside the loop",
                    Pattern.compile("touched_at = EXCLUDED\\.touched_at; DELETE FROM producto_talle"
                            + ".*?IF v_prev_precio IS NULL THEN"),
                    "touched_at = EXCLUDED.touched_at; IF v_prev_precio IS NULL THEN")
    );

    /**
     * close-1nf-and-3nf-foundation, design DD7: V17 retypes {@code precio_orig}
     * to {@code double precision} and the INSERT VALUES expression that used
     * to hand {@code sp_upsert_run} a raw JSON string now casts it. Exactly
     * ONE line changes — the {@code ON CONFLICT} line needs no edit, EXCLUDED
     * already carries the typed value once the INSERT VALUES cast lands.
     */
    private static final Substitution SP_UPSERT_RUN_V17_PRECIO_ORIG_CAST = new Substitution(
            "INSERT VALUES: precioOrig gains a (::DOUBLE PRECISION) cast (D1/DD7 — Double retype)",
            Pattern.compile(Pattern.quote("(r->>'precioOrig')::DOUBLE PRECISION")),
            "r->>'precioOrig'");

    /**
     * close-1nf-and-3nf-foundation extension, design E4/E6 (discovered
     * mid-apply, not in the original design): the {@code fk_productos_marca}
     * FK that V21 adds rejects {@code ''} immediately, and Java's
     * {@code BrandExtractor} always emits {@code marca:""} for an abstained
     * product (never omits the key), so {@code COALESCE(r->>'marca', '')}
     * would violate the FK for the very first abstained-brand upsert after
     * V21 applies — a same-commit break, not the deploy-time race the
     * original design assumed away. Moved here instead of staying folded
     * into the single V23 the design planned, specifically so V21's own
     * commit keeps the full suite green (`TEST-1`). V23 still lands the
     * other two substitutions (marca_premium removal, sitio get-or-create).
     */
    private static final Substitution SP_UPSERT_RUN_V21_MARCA_NULLIF = new Substitution(
            "INSERT VALUES: marca gains nullif('') instead of COALESCE('') (design E4, FK abstention)",
            Pattern.compile(Pattern.quote("nullif(r->>'marca','')")),
            "COALESCE(r->>'marca', '')");

    /**
     * close-1nf-and-3nf-foundation extension, design E2: {@code marca_premium}
     * is a transitive dependency of {@code sitio}, not of {@code url}
     * ({@code SITIOS_PREMIUM} keyed the value off the SITE all along, despite
     * the column name), so V22 drops it from {@code productos} and the value is
     * resolved in Java from the already-in-memory {@code SiteRegistry}.
     *
     * <p>THREE substitutions, and the third is the reason this list is spelled
     * out rather than trusted to review: {@code rg marca_premium} finds only
     * TWO of the three sites. The INSERT VALUES expression reads the camelCase
     * JSON key {@code (r->>'marcaPremium')}, so a grep-driven cleanup removes
     * the column from the list and the SET, leaves the value behind, and ships
     * an INSERT with 26 columns and 27 values. {@code CREATE FUNCTION} does not
     * catch it — plpgsql bodies are late-bound — so the migration applies
     * cleanly and the break surfaces at the first upsert, where
     * {@code ProductRepository} swallows it and reports {@code "0 nuevos"}.</p>
     */
    private static final List<Substitution> SP_UPSERT_RUN_V22_DROP_MARCA_PREMIUM = List.of(
            new Substitution(
                    "INSERT column list: marca_premium removed (design E2)",
                    Pattern.compile(Pattern.quote("rubro, marca, gymrat, cantidad_unidades,")),
                    "rubro, marca, gymrat, marca_premium, cantidad_unidades,"),
            new Substitution(
                    "INSERT VALUES: the (r->>'marcaPremium') expression removed — the grep-invisible one",
                    Pattern.compile(Pattern.quote(
                            "COALESCE((r->>'gymrat')::boolean, false), COALESCE((r->>'cantidadUnidades')::INTEGER, 1),")),
                    "COALESCE((r->>'gymrat')::boolean, false), COALESCE((r->>'marcaPremium')::boolean, false), "
                            + "COALESCE((r->>'cantidadUnidades')::INTEGER, 1),"),
            new Substitution(
                    "ON CONFLICT SET: marca_premium assignment removed (design E2)",
                    Pattern.compile(Pattern.quote("gymrat = EXCLUDED.gymrat, cantidad_unidades")),
                    "gymrat = EXCLUDED.gymrat, marca_premium = EXCLUDED.marca_premium, cantidad_unidades"));

    /**
     * close-1nf-and-3nf-foundation extension, design E7: `V23` puts a real FK
     * on {@code productos.sitio}, and the function gains a get-or-create so
     * that FK is unfalsifiable for anything the scraper writes — without it, a
     * site not yet in {@code sitio} would violate the constraint inside
     * {@code ProductRepository}'s swallowed-error path and surface as
     * {@code "0 nuevos"} rather than as a failure.
     *
     * <p>This is the first change declared against a REPEATABLE migration
     * instead of a new versioned copy, and it is what the `R__` move was for:
     * editing the one file turned this hop red on its own, demanding the
     * declaration. From here the git diff carries the rest of the story.</p>
     */
    private static final Substitution SP_UPSERT_RUN_E7_SITIO_GET_OR_CREATE = new Substitution(
            "get-or-create de sitio antes del INSERT de producto (design E7, FK fk_productos_sitio)",
            Pattern.compile("-- get-or-create de .*?ON CONFLICT DO NOTHING; "),
            "");

    /**
     * El soft-delete pasa a estar acotado por sitio: la firma gana un tercer
     * argumento {@code p_sitios} y el UPDATE gana la condición
     * {@code sitio = ANY(p_sitios)}. Antes barría el catálogo entero, así que un
     * run de un subconjunto de sitios desactivaba todo lo demás.
     *
     * <p>Se declaran las DOS mitades por separado a propósito: si mañana alguien
     * agrega el parámetro y se olvida la condición del WHERE (o al revés), el
     * hop queda rojo en vez de pasar por "ya está declarado".</p>
     */
    private static final List<Substitution> SP_SOFT_DELETE_AUSENTES_R_SITE_SCOPE = List.of(
            new Substitution(
                    "tercer argumento p_sitios en la firma",
                    Pattern.compile("\\(p_urls text\\[\\], p_now text, p_sitios text\\[\\]\\)"),
                    "(p_urls text[], p_now text)"),
            new Substitution(
                    "condición de alcance por sitio en el UPDATE",
                    Pattern.compile("WHERE activo AND sitio = ANY\\(p_sitios\\) AND NOT"),
                    "WHERE activo AND NOT"));

    private static final List<Hop> CHAIN = List.of(
            new Hop(SP_UPSERT_RUN_START, V1, V3, List.of(UNGUARD_LOCKED_COLUMNS)),
            new Hop(SP_UPSERT_RUN_START, V3, V5, SP_UPSERT_RUN_V5_CASTS),
            new Hop(SP_UPSERT_RUN_START, V5, V7, SP_UPSERT_RUN_V7_CHILD_TABLES),
            new Hop(SP_UPSERT_RUN_START, V7, V17, List.of(SP_UPSERT_RUN_V17_PRECIO_ORIG_CAST)),
            new Hop(SP_UPSERT_RUN_START, V17, V21, List.of(SP_UPSERT_RUN_V21_MARCA_NULLIF)),
            new Hop(SP_UPSERT_RUN_START, V21, V22, SP_UPSERT_RUN_V22_DROP_MARCA_PREMIUM),
            new Hop(SP_SOFT_DELETE_AUSENTES_START, V1, V5, SP_SOFT_DELETE_AUSENTES_V5_CASTS),

            // Los dos saltos finales, hacia las migraciones REPETIBLES, con
            // CERO sustituciones declaradas. Un salto sin sustituciones no
            // afloja nada: la aserción de igualdad sigue corriendo, así que
            // exige que el cuerpo del R__ sea idéntico —carácter por carácter,
            // normalizando espacios— al de la última copia versionada. Eso es
            // exactamente lo que hay que probar al mover una definición: que
            // mover no cambió.
            //
            // Cuando el R__ cambie de verdad, ESTE salto es el que se pone en
            // rojo, y ahí el diff de git pasa a ser la declaración del cambio
            // — que es como debería haber funcionado desde el principio, en
            // vez de con siete copias y una tabla de sustituciones.
            new Hop(SP_UPSERT_RUN_START, V22, R_UPSERT, List.of(SP_UPSERT_RUN_E7_SITIO_GET_OR_CREATE)),
            new Hop(SP_SOFT_DELETE_AUSENTES_START, V5, R_SOFT_DELETE,
                    SP_SOFT_DELETE_AUSENTES_R_SITE_SCOPE)
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
