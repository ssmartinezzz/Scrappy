package ar.scraper.catalog;

import ar.scraper.model.Product;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read/write/audit port for the product aggregate: the scrape write-path,
 * catalog reads, the machine and human classification paths, and the
 * destructive catalog clear.
 *
 * <p>Extracted like {@code FavoritosPort} (extract-favoritos-port) so
 * {@code ar.scraper.web} depends on this port, not on {@code DatabaseService}
 * directly (extract-catalog-query-port). Names are unchanged — they already
 * equal the {@code DatabaseService} facade names {@code ProductRepository}
 * implements today.</p>
 */
public interface ProductPort {

    // ─── read ──────────────────────────────────────────────────────────────

    Optional<Product> obtenerProducto(String url);

    Optional<Product> obtenerProductoPorKey(String key);

    List<Product> cargarProductos();

    Map<String, ClasificacionBloqueada> cargarClasificacionBloqueada();

    boolean estaBloqueado(String url);

    boolean esProductoActivo(String url);

    long contarEmbeddings();

    // ─── write ─────────────────────────────────────────────────────────────

    UpsertStats upsertProductos(List<Product> productos);

    /** Mismo merge, con el alcance del soft-delete derivado de la CORRIDA y no del batch. */
    UpsertStats upsertProductos(List<Product> productos, ar.scraper.scrape.CorridaEnCurso corrida);

    void upsertParcial(List<Product> productos);

    void actualizarCategoria(String url, String nuevaCategoria);

    int actualizarNormalizacion(String url, String categoria, String marca, String genero,
                                List<String> talles, String subCategoria);

    void marcarDescontinuado(String url);

    void limpiarProductos() throws SQLException;

    // ─── audited human path ────────────────────────────────────────────────

    boolean aplicarReclasificacionAuditada(String url, String categoria, String marca,
                                           String genero, List<String> talles, String subCategoria,
                                           Product previo, String actor);
}
