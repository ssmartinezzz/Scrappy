package ar.scraper.ml;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Environment resolution for the Python subprocesses: DSN translation and the
 * models/HF cache roots.
 *
 * <p>Extracted verbatim from {@link PythonRunner} (backlog A3). All of it is
 * pure — {@code envModelsRoot} is an explicit parameter rather than a
 * {@code System.getenv} read precisely so tests can inject a value without
 * mutating the JVM environment. {@link PythonRunner} keeps the package-private
 * seams delegating here, since the tests call them through that class.</p>
 */
final class PythonEnv {

    private PythonEnv() {}

    /**
     * Translates the JVM's own {@code DATABASE_URL} (JDBC format —
     * {@code jdbc:postgresql://host:port/db}, required by Spring's
     * {@code spring.datasource.url}/HikariCP) into a libpq/psycopg2-compatible
     * DSN ({@code postgresql://host:port/db[?user=...&password=...]}) for the
     * Python subprocess env.
     *
     * <p>Batch 2 forwarded {@code DATABASE_URL} to Python verbatim, which
     * silently breaks {@code psycopg2.connect(dsn)} the moment the value is
     * a real {@code jdbc:} URL: libpq only recognizes the
     * {@code postgresql://}/{@code postgres://} schemes, not {@code jdbc:}.</p>
     *
     * <p>{@code username}/{@code password} are appended as libpq URI query
     * parameters only when non-blank — local dev's trust auth needs at least a
     * {@code user} param since psycopg2 otherwise defaults to the OS username,
     * which does not match the {@code postgres} role.</p>
     */
    static String toPsycopgDsn(String jdbcOrPlainUrl, String username, String password) {
        if (jdbcOrPlainUrl == null) {
            return null;
        }
        String plain = jdbcOrPlainUrl.startsWith("jdbc:")
                ? jdbcOrPlainUrl.substring("jdbc:".length())
                : jdbcOrPlainUrl;
        StringBuilder query = new StringBuilder();
        if (username != null && !username.isBlank()) {
            query.append("user=").append(username);
        }
        if (password != null && !password.isBlank()) {
            if (query.length() > 0) query.append('&');
            query.append("password=").append(password);
        }
        if (query.length() == 0) {
            return plain;
        }
        return plain + (plain.contains("?") ? "&" : "?") + query;
    }

    /**
     * Resolves {@code SCRAPER_MODELS_ROOT} for a Python subprocess env. Falls
     * back to {@code workDir.resolve("_models")} — the SAME models dir Java
     * itself resolves for the training-model freshness check, so
     * scoring/training/backfill subprocesses and the JVM always agree on one
     * models directory even when the env var isn't set.
     */
    static String resolveModelsRoot(String envModelsRoot, Path workDir) {
        if (envModelsRoot != null && !envModelsRoot.isBlank()) return envModelsRoot;
        return workDir.resolve("_models").toString();
    }

    /**
     * {@code <modelsRoot>/marqo} — same shape the installer pins and
     * {@code ml_embeddings.py}'s own fallback ({@code _default_hf_home()}),
     * derived from {@code SCRAPER_MODELS_ROOT} rather than a DB file path
     * (design D5).
     */
    static String hfHomeParaModelsRoot(String modelsRoot) {
        return Paths.get(modelsRoot).resolve("marqo").toString();
    }

    /** Applies DATABASE_URL / SCRAPER_MODELS_ROOT / HF_HOME onto a subprocess builder. */
    static void aplicar(ProcessBuilder pb, Path workDir) {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl != null) {
            String psycopgDsn = toPsycopgDsn(databaseUrl,
                    System.getenv("DATABASE_USERNAME"), System.getenv("DATABASE_PASSWORD"));
            pb.environment().put("DATABASE_URL", psycopgDsn);
        }
        String modelsRoot = resolveModelsRoot(System.getenv("SCRAPER_MODELS_ROOT"), workDir);
        pb.environment().put("SCRAPER_MODELS_ROOT", modelsRoot);
        pb.environment().put("HF_HOME", hfHomeParaModelsRoot(modelsRoot));
    }
}
