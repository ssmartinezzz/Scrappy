package ar.scraper.aggregator;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;

/**
 * Lectura del snapshot vivo del catalogo: lo ultimo que dejo una corrida de
 * scraping, que es contra lo que responden las superficies en memoria
 * ({@code /api/grupos}, {@code /api/mejores}, outfits, recomendados, agente).
 *
 * <p>Vive en {@code aggregator} y no en un area por la misma regla que F2 le
 * aplico a los 13 puertos de persistencia: el puerto vive donde vive el tipo
 * que devuelve, y {@link AggregatedResult} se declara aca. Un puerto en
 * {@code catalog} que devolviera un tipo de {@code aggregator} violaria
 * {@code areasSonSumideros}.</p>
 *
 * <p>Lo implementa {@code ar.scraper.web.ScraperService}, que es quien tiene el
 * snapshot. Los tres tools del agente dependen de esta capacidad y ya no
 * nombran {@code ar.scraper.web}: con eso muere el ciclo {@code agent <-> web},
 * el septimo y ultimo de los congelados.</p>
 */
public interface CatalogSnapshotPort {

    /** El ultimo resultado agregado, o {@code null} si todavia no corrio ninguno. */
    AggregatedResult getLastResult();
}
