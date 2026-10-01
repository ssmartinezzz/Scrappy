package ar.scraper.ml;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.lang3.StringUtils;

/**
 * DSN translation and the models/HF cache roots. All of it is pure — {@code envModelsRoot} is an
 * explicit parameter rather than a {@code System.getenv} read precisely so tests can inject a value
 * without mutating the JVM environment.
 */
final class PythonEnv {

    private PythonEnv() {}

    /**
     * Translates the JVM's own {@code DATABASE_URL} (JDBC format —
     * {@code jdbc:postgresql://host:port/db}, required by Spring's
     * {@code spring.datasource.url}/HikariCP) into a libpq/psycopg2-compatible DSN
     * ({@code postgresql://host:port/db[?user=...&password=...]}) for the Python subprocess env.
     */
    static String toPsycopgDsn(String jdbcOrPlainUrl, String username, String password) {
        if (jdbcOrPlainUrl == null) {
            return null;
        }
        String plain = jdbcOrPlainUrl.startsWith("jdbc:")
                ? jdbcOrPlainUrl.substring("jdbc:".length())
                : jdbcOrPlainUrl;
        StringBuilder query = new StringBuilder();
        if (StringUtils.isNotBlank(username)) {
            query.append("user=").append(username);
        }
        if (StringUtils.isNotBlank(password)) {
            if (query.length() > 0) query.append('&');
            query.append("password=").append(password);
        }
        if (query.length() == 0) {
            return plain;
        }
        return plain + (plain.contains("?") ? "&" : "?") + query;
    }

    static String resolveModelsRoot(String envModelsRoot, Path workDir) {
        if (StringUtils.isNotBlank(envModelsRoot)) return envModelsRoot;
        return workDir.resolve("_models").toString();
    }

    /**
     * {@code <modelsRoot>/marqo} — same shape the installer pins and {@code ml_embeddings.py}'s own
     * fallback ({@code _default_hf_home()}), derived from {@code SCRAPER_MODELS_ROOT} rather than a
     * DB file path.
     */
    static String hfHomeParaModelsRoot(String modelsRoot) {
        return Paths.get(modelsRoot).resolve("marqo").toString();
    }

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
