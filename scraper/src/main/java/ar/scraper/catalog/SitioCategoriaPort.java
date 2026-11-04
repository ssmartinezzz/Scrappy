package ar.scraper.catalog;

import ar.scraper.model.SitioCategoriaNodo;

import java.util.List;
import java.util.Map;

public interface SitioCategoriaPort {

    int upsertArbol(String sitio, List<SitioCategoriaNodo> arbol);

    Map<Integer, String> categoriaPorOrigen(String sitio);
}
