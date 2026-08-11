package ar.scraper.db.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice A.2 (V5), task 2.2.
 *
 * <p>Covers spec "db-column-domains" (design D2) — no integer-boolean idiom
 * may survive the 8-column BOOLEAN cutover. Mechanical, no-DB, same family
 * as {@link StoredProcedureDriftTest}: reads {@code src/main/java/ar/scraper/db/*.java}
 * and {@code src/main/resources/ml/*.py} from disk (not the classpath — the
 * Java sources are not packaged resources) and fails on any of the three
 * idioms design D2 identifies:</p>
 * <ol>
 *   <li>{@code rs.getInt("<col>")} for one of the 8 boolean columns —
 *       {@code getInt} on a now-{@code boolean} column throws at runtime.</li>
 *   <li>{@code ps.setInt(n, x ? 1 : 0)} — the boolean-to-int ternary idiom,
 *       independent of column name (a raw {@code setInt} index carries no
 *       column name to grep for).</li>
 *   <li>{@code <col> = [01]} / {@code <col>=[01]} raw SQL literal comparisons
 *       for one of the 8 boolean columns — {@code PresetRepository.java:169}
 *       (deactivate-all) is exactly the kind of path an integration test does
 *       not reach.</li>
 * </ol>
 *
 * <p>This is a static proxy for the runtime {@code PSQLException} the old
 * idiom would throw once the column is actually BOOLEAN — it exists so the
 * guard fires at test time, not in a scrape run against a stranger's DB.</p>
 */
class NoIntegerBooleanLiteralsTest {

    /** The 8 boolean column names retyped by V5 (design D2) — "activo" covers two tables. */
    private static final List<String> BOOLEAN_COLUMNS = List.of(
            "activo", "gymrat", "marca_premium", "ml_oferta",
            "enabled", "liked", "force_retrain", "use_gpu");

    private static final Pattern SET_INT_TERNARY = Pattern.compile(
            "setInt\\([^;]*?\\?\\s*1\\s*:\\s*0\\s*\\)", Pattern.DOTALL);

    private record Violation(String file, String line, String reason) {
        @Override
        public String toString() {
            return file + ": [" + reason + "] " + line.strip();
        }
    }

    @Test
    @DisplayName("no getInt/setInt/raw-literal integer-boolean idiom survives for the 8 retyped columns")
    void noIntegerBooleanIdiomSurvives() throws IOException {
        List<Violation> violations = new ArrayList<>();

        Path dbSourceDir = resolveDir("scraper/src/main/java/ar/scraper/db");
        for (Path javaFile : listFiles(dbSourceDir, ".java")) {
            String content = Files.readString(javaFile);
            scanGetInt(javaFile, content, violations);
            scanSetIntTernary(javaFile, content, violations);
            scanRawLiteral(javaFile, content, violations);
        }

        Path mlSourceDir = resolveDir("scraper/src/main/resources/ml");
        for (Path pyFile : listFiles(mlSourceDir, ".py")) {
            String content = Files.readString(pyFile);
            scanRawLiteral(pyFile, content, violations);
        }

        assertThat(violations)
                .as("no integer-boolean idiom for %s — see design D2 (%d files scanned)",
                        BOOLEAN_COLUMNS, dbSourceDir == null ? 0 : 1)
                .isEmpty();
    }

    private void scanGetInt(Path file, String content, List<Violation> violations) {
        for (String col : BOOLEAN_COLUMNS) {
            Pattern p = Pattern.compile("getInt\\(\\s*\"" + Pattern.quote(col) + "\"\\s*\\)");
            forEachLineMatch(file, content, p, "getInt(\"" + col + "\")", violations);
        }
    }

    private void scanSetIntTernary(Path file, String content, List<Violation> violations) {
        Matcher m = SET_INT_TERNARY.matcher(content);
        while (m.find()) {
            String snippet = m.group();
            int line = 1 + (int) content.substring(0, m.start()).chars().filter(ch -> ch == '\n').count();
            violations.add(new Violation(file.toString(), snippet.replace("\n", " "),
                    "setInt(n, x ? 1 : 0) boolean-to-int ternary at line " + line));
        }
    }

    private void scanRawLiteral(Path file, String content, List<Violation> violations) {
        for (String col : BOOLEAN_COLUMNS) {
            // <col> = 0|1  or  <col>=0|1 — word-boundary on the column name, optional
            // whitespace around '=', literal 0 or 1 with a boundary after it.
            Pattern p = Pattern.compile("\\b" + Pattern.quote(col) + "\\b\\s*=\\s*[01]\\b");
            forEachLineMatch(file, content, p, col + " = 0|1 raw literal", violations);
        }
    }

    private void forEachLineMatch(Path file, String content, Pattern pattern, String reason,
                                   List<Violation> violations) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = pattern.matcher(lines[i]);
            if (m.find()) {
                violations.add(new Violation(file + ":" + (i + 1), lines[i], reason));
            }
        }
    }

    private static List<Path> listFiles(Path dir, String suffix) throws IOException {
        List<Path> result = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + suffix)) {
            for (Path p : stream) {
                result.add(p);
            }
        }
        return result;
    }

    /** Resolves a repo-root-relative path by walking up from the working directory, bounded. */
    private static Path resolveDir(String repoRelative) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            // Also try without the leading "scraper/" in case cwd is already the module dir.
            String withoutScraper = repoRelative.startsWith("scraper/") ? repoRelative.substring("scraper/".length()) : null;
            if (withoutScraper != null) {
                Path alt = dir.resolve(withoutScraper);
                if (Files.isDirectory(alt)) {
                    return alt;
                }
            }
            dir = dir.getParent();
        }
        throw new UncheckedIOException(new IOException("Could not resolve directory for " + repoRelative
                + " walking up from " + System.getProperty("user.dir")));
    }
}
