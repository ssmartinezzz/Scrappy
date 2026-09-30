package ar.scraper.ml;

import ar.scraper.catalog.CategoriaStats;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Method names are the repository's, not the facade's ({@code DatabaseService} keeps its own
 * spelling and renames while delegating).
 */
public interface CategoriaStatsPort {

    void guardarCategoriaStats(JsonNode statsNode);

    Map<String, CategoriaStats> cargarCategoriaStats();
}
