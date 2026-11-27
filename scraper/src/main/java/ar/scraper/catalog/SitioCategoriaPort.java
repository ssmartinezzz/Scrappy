package ar.scraper.catalog;

import ar.scraper.model.SitioCategoriaNodo;

import java.util.List;
import java.util.Map;

public interface SitioCategoriaPort {

    int upsertArbol(String sitio, List<SitioCategoriaNodo> arbol);

    Map<Integer, String> categoriaPorOrigen(String sitio);

    /**
     * The curated sub-category of each leaf of the site that has one, keyed like {@link #categoriaPorOrigen}. It is only ever
     * read together with the leaf's category: a sub without a category is not a valid row (V144).
     */
    default Map<Integer, String> subCategoriaPorOrigen(String sitio) {
        return Map.of();
    }
}
