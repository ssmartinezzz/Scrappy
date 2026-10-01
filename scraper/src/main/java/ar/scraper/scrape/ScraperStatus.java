package ar.scraper.scrape;

/**
 * {@link ScrapeControlPort} lo devuelve, y el scheduler lo lee sin nombrar {@code ar.scraper.web}.
 */
public enum ScraperStatus { IDLE, RUNNING, DONE, ERROR }
