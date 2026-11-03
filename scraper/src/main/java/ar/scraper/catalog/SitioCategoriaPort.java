package ar.scraper.catalog;

import ar.scraper.model.SitioCategoriaNodo;

import java.util.List;

public interface SitioCategoriaPort {

    int upsertArbol(String sitio, List<SitioCategoriaNodo> arbol);
}
