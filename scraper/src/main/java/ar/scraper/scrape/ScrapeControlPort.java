package ar.scraper.scrape;

import java.util.Set;

/**
 * Su unico consumidor es el scheduler ({@code ar.scraper.scheduling}), que antes de este puerto
 * dependia de tres clases de infraestructura a la vez:
 */
public interface ScrapeControlPort {

    ScraperStatus estado();

    boolean iniciar(Set<String> sitiosSeleccionados, boolean forceRetrain);

    double precioMinimo();

    double precioMaximo();

    void aplicarBandaDePrecio(double minimo, double maximo);

    void usarGpu(boolean usar);
}
