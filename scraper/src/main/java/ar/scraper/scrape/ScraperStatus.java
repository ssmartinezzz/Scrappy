package ar.scraper.scrape;

/**
 * Estado de una corrida de scraping, tal como lo publica {@code /api/status}.
 *
 * <p>Vivio anidado en {@code ar.scraper.web.ScraperService} hasta
 * {@code close-backend-package-cycles} (F3a). Se promueve al area por el mismo
 * motivo que {@code UpsertStats}, {@code Preset} y {@code HistorialEntry} en
 * F2: un puerto que vive en un area no puede devolver un tipo declarado en un
 * paquete de infraestructura. {@link ScrapeControlPort} lo devuelve, y el
 * scheduler lo lee sin nombrar {@code ar.scraper.web}.</p>
 *
 * <p>Los cuatro nombres de constante no cambian, asi que la serializacion JSON
 * del endpoint de status es identica byte a byte.</p>
 */
public enum ScraperStatus { IDLE, RUNNING, DONE, ERROR }
