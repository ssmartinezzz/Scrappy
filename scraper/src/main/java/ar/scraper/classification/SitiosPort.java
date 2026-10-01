package ar.scraper.classification;

import java.util.List;
import java.util.Map;

/**
 * An implementation that skips that reload is wrong even though it compiles. Note what is NOT here:
 */
public interface SitiosPort {

    void guardarSitio(String nombre, String url, String plataforma);

    void eliminarSitio(String nombre);

    List<Map<String, String>> cargarSitiosDinamicos();
}
