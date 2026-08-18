package ar.scraper.db.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lee el bloque de rollback documentado para una migración y lo devuelve tal
 * cual, para que el test lo EJECUTE contra Postgres.
 *
 * <p>Una migración aplicada es byte-frozen —Flyway valida checksums— así que el
 * SQL de rollback no puede vivir dentro del propio {@code .sql}. Vive en la
 * documentación, y estos tests son lo que impide que la documentación se
 * desincronice: si alguien edita el bloque y deja SQL que no corre, el test se
 * pone rojo.</p>
 *
 * <p>Esta clase existe porque ese lector estaba copiado, verbatim, en los diez
 * {@code V*RollbackRoundTripTest} — mismas ~28 líneas, variando sólo el número
 * de versión. El costo no era la repetición sino que el NOMBRE DEL ARCHIVO
 * quedaba escrito en diez lugares: mover la documentación de sitio obligaba a
 * acertarle a los diez o dejar tests leyendo un archivo que ya no existe. Acá
 * el nombre se escribe una sola vez ({@code DOC}).</p>
 */
final class DocumentedRollback {

    /** El documento que es dueño del SQL de rollback de todas las migraciones. */
    private static final String DOC = "DATABASE.md";

    private static final int MAX_NIVELES_HACIA_ARRIBA = 6;

    private DocumentedRollback() {
    }

    /**
     * @param version etiqueta de la migración tal como aparece en los marcadores,
     *                p.ej. {@code "V23"} para {@code -- >>> rollback:V23}.
     */
    static String sqlFor(String version) {
        String doc = leerDoc();
        String open = "-- >>> rollback:" + version;
        String close = "-- <<< rollback:" + version;

        int start = doc.indexOf(open);
        assertThat(start).as("marcador de apertura '%s' presente en docs/%s", open, DOC).isNotEqualTo(-1);
        int end = doc.indexOf(close, start);
        assertThat(end).as("marcador de cierre '%s' presente en docs/%s", close, DOC).isNotEqualTo(-1);

        String sql = doc.substring(start + open.length(), end).trim();
        assertThat(sql).as("el bloque de rollback documentado para %s no está vacío", version).isNotEmpty();
        return sql;
    }

    private static String leerDoc() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < MAX_NIVELES_HACIA_ARRIBA && dir != null; i++) {
            Path candidate = dir.resolve("docs").resolve(DOC);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("docs/" + DOC + " no encontrado subiendo desde "
                + System.getProperty("user.dir"));
    }
}
