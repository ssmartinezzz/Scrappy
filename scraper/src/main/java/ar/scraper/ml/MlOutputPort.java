package ar.scraper.ml;

import com.fasterxml.jackson.databind.JsonNode;


/**
 * {@code ar.scraper.ml} is infrastructure — the Python subprocess runner and the enrichers — and
 * {@code areasSonSumideros} lists it among the packages an area may not depend on.
 */
public interface MlOutputPort {

    void guardarMlOutput(JsonNode mlOutput);

    JsonNode cargarMlOutput();

    void limpiarMlOutput();
}
