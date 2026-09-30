package ar.scraper.catalog;

import ar.scraper.model.PersistenciaException;

/**
 * Thrown by {@link ProductPort#limpiarProductos()} when one or more {@code favoritos} rows still
 * reference a live product. Mirrors the {@code RESTRICT} FK on {@code favoritos.url} so the
 * caller gets an actionable count instead of an opaque 500. There is deliberately no
 * {@code ?force=} override.
 */
public class FavoritosProtegidosException extends PersistenciaException {

    private final long favoritosBloqueantes;

    public FavoritosProtegidosException(long favoritosBloqueantes) {
        super("No se puede vaciar el catálogo: " + favoritosBloqueantes
                + " producto(s) favorito(s) todavía existen.");
        this.favoritosBloqueantes = favoritosBloqueantes;
    }

    public long getFavoritosBloqueantes() {
        return favoritosBloqueantes;
    }
}
