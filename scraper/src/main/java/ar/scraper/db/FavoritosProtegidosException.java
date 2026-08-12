package ar.scraper.db;

import java.sql.SQLException;

/**
 * normalize-db-schema-fks-1nf, slice A.1 (design D9).
 *
 * <p>Thrown by {@link ProductRepository#limpiarProductos()} when one or more
 * {@code favoritos} rows still reference a live product. Mirrors the FK
 * {@code RESTRICT} policy on {@code favoritos.url} (V4, design D8) at the
 * application layer so the caller gets an actionable count instead of a raw
 * FK-violation {@link SQLException} surfacing as an opaque 500. There is
 * deliberately no {@code ?force=} override for this (spec "Catalog-wipe
 * contract").</p>
 */
public class FavoritosProtegidosException extends SQLException {

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
