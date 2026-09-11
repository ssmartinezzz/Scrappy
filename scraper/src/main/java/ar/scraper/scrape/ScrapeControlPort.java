package ar.scraper.scrape;

import java.util.Set;

/**
 * Todo lo que hace falta para lanzar y parametrizar una corrida de scraping,
 * desde afuera de la corrida.
 *
 * <p>Su unico consumidor es el scheduler ({@code ar.scraper.scheduling}), que
 * antes de este puerto dependia de tres clases de infraestructura a la vez:
 * {@code web.ScraperService} (estado y disparo), {@code config.ScraperConfig}
 * (banda de precio) y {@code ml.PythonRunner} (flag GPU). Las tres estan
 * prohibidas para un area por {@code areasSonSumideros}, asi que absorber
 * {@code cron/} en {@code scheduling/} exigia una sola costura en lugar de
 * tres.</p>
 *
 * <p>No es un grab-bag: las seis firmas son exactamente la receta de
 * {@code POST /api/scrape} que el cron job replica — capturar la
 * configuracion, aplicarla, disparar, esperar, restaurar.</p>
 */
public interface ScrapeControlPort {

    /** Estado actual del scraper. */
    ScraperStatus estado();

    /**
     * Dispara una corrida. Devuelve {@code false} si otra ya estaba en curso
     * (el scheduler lo usa para detectar la carrera TOCTOU contra su propio
     * guard).
     */
    boolean iniciar(Set<String> sitiosSeleccionados, boolean forceRetrain);

    /** Piso de la banda de precio vigente, para poder restaurarlo despues. */
    double precioMinimo();

    /** Techo de la banda de precio vigente, para poder restaurarlo despues. */
    double precioMaximo();

    /** Aplica la banda de precio de este job; el scheduler restaura la previa en {@code finally}. */
    void aplicarBandaDePrecio(double minimo, double maximo);

    /** Aplica el flag de GPU de este job; el scheduler restaura {@code true} en {@code finally}. */
    void usarGpu(boolean usar);
}
