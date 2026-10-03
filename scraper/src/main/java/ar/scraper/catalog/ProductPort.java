package ar.scraper.catalog;

import ar.scraper.model.Product;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ProductPort {

    Optional<Product> obtenerProducto(String url);

    Optional<Product> obtenerProductoPorKey(String key);

    List<Product> cargarProductos();

    Map<String, ClasificacionBloqueada> cargarClasificacionBloqueada();

    boolean estaBloqueado(String url);

    boolean esProductoActivo(String url);

    long contarEmbeddings();

    UpsertStats upsertProductos(List<Product> productos);

    UpsertStats upsertProductos(List<Product> productos, ar.scraper.scrape.CorridaEnCurso corrida);

    UpsertStats upsertParcial(List<Product> productos);

    void actualizarCategoria(String url, String nuevaCategoria);

    int actualizarNormalizacion(String url, String categoria, String marca, String genero,
                                List<String> talles, String subCategoria);

    void marcarDescontinuado(String url);

    void limpiarProductos();

    boolean aplicarReclasificacionAuditada(String url, String categoria, String marca,
                                           String genero, List<String> talles, String subCategoria,
                                           Product previo, String actor);
}
