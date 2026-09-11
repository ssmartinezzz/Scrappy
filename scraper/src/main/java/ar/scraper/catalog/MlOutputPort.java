package ar.scraper.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;

/**
 * Capability port for the {@code ml_output} aggregate: the whole JSON payload
 * one ML pipeline run produces, stored verbatim and replayed on boot so a
 * restart does not lose the last run's scores (extract-ml-persistence-ports,
 * port 8 of 13).
 *
 * <p>It lives in {@code catalog} rather than in an {@code ml} area of its own
 * because there is no such area: {@code ar.scraper.ml} is infrastructure — the
 * Python subprocess runner and the enrichers — and {@code areasSonSumideros}
 * lists it among the packages an area may not depend on. The payload it stores
 * is catalog analytics, so the catalog area owns it.</p>
 */
public interface MlOutputPort {

    /** Stores one run's output, replacing whatever was there. */
    void guardarMlOutput(JsonNode mlOutput);

    /** Reads the last stored run back, or a missing node when there is none. */
    JsonNode cargarMlOutput();

    /** Drops the stored run. Used by the destructive admin surface. */
    void limpiarMlOutput() throws SQLException;
}
